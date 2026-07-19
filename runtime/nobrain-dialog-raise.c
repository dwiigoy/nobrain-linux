#define _DEFAULT_SOURCE

#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define LOCK_PATH "/tmp/nobrain-dialog-raise.lock"
#define LOG_PATH "/tmp/nobrain-dialog-raise.log"

static Display *display;
static Window root;
static Atom atom_pid;
static Atom atom_window_type;
static Atom atom_dialog;
static volatile sig_atomic_t running = 1;
static int lock_fd = -1;
static void log_line(const char *format, ...);

static int
handle_x_error(Display *error_display, XErrorEvent *event)
{
	char message[128];

	XGetErrorText(error_display, event->error_code, message, sizeof message);
	log_line("X error request=%u minor=%u resource=0x%lx: %s",
	         event->request_code, event->minor_code, event->resourceid, message);
	return 0;
}

static void
log_line(const char *format, ...)
{
	FILE *stream;
	time_t now;
	struct tm local;
	char timestamp[32];
	va_list arguments;

	stream = fopen(LOG_PATH, "a");
	if (!stream)
		return;
	now = time(NULL);
	localtime_r(&now, &local);
	strftime(timestamp, sizeof timestamp, "%Y-%m-%dT%H:%M:%S", &local);
	fprintf(stream, "%s ", timestamp);
	va_start(arguments, format);
	vfprintf(stream, format, arguments);
	va_end(arguments);
	fputc('\n', stream);
	fclose(stream);
}

static void
stop_running(int signal_number)
{
	(void)signal_number;
	running = 0;
}

static bool
get_cardinal(Window window, Atom property, unsigned long *value)
{
	Atom actual_type;
	int actual_format;
	unsigned long count;
	unsigned long remaining;
	unsigned char *data = NULL;
	int result;

	result = XGetWindowProperty(display, window, property, 0, 1, False,
	                            XA_CARDINAL, &actual_type, &actual_format,
	                            &count, &remaining, &data);
	if (result != Success || actual_type != XA_CARDINAL || actual_format != 32
	    || count != 1 || !data) {
		if (data)
			XFree(data);
		return false;
	}
	*value = *(unsigned long *)data;
	XFree(data);
	return true;
}

static bool
is_dialog(Window window)
{
	Atom actual_type;
	int actual_format;
	unsigned long count;
	unsigned long remaining;
	unsigned char *data = NULL;
	unsigned long index;
	bool result = false;

	if (XGetWindowProperty(display, window, atom_window_type, 0, 16, False,
	                       XA_ATOM, &actual_type, &actual_format, &count,
	                       &remaining, &data) != Success || !data)
		return false;
	if (actual_type == XA_ATOM && actual_format == 32) {
		Atom *atoms = (Atom *)data;
		for (index = 0; index < count; ++index) {
			if (atoms[index] == atom_dialog) {
				result = true;
				break;
			}
		}
	}
	XFree(data);
	return result;
}

static bool
is_mapped(Window window)
{
	XWindowAttributes attributes;
	return XGetWindowAttributes(display, window, &attributes)
	       && attributes.map_state == IsViewable;
}

static bool
get_class(Window window, char *buffer, size_t size)
{
	XClassHint hint = {0};
	const char *source;
	size_t index;

	if (!XGetClassHint(display, window, &hint))
		return false;
	source = hint.res_class ? hint.res_class : hint.res_name;
	if (!source) {
		if (hint.res_name)
			XFree(hint.res_name);
		if (hint.res_class)
			XFree(hint.res_class);
		return false;
	}
	for (index = 0; index + 1 < size && source[index]; ++index)
		buffer[index] = (char)tolower((unsigned char)source[index]);
	buffer[index] = '\0';
	if (hint.res_name)
		XFree(hint.res_name);
	if (hint.res_class)
		XFree(hint.res_class);
	return true;
}

static bool
is_wps_class(Window window)
{
	char class_name[64];
	if (!get_class(window, class_name, sizeof class_name))
		return false;
	return strcmp(class_name, "wps") == 0 || strcmp(class_name, "et") == 0
	       || strcmp(class_name, "wpp") == 0;
}

static bool
has_legacy_name(Window window)
{
	XTextProperty property = {0};
	bool result;

	if (!XGetWMName(display, window, &property) || !property.value)
		return false;
	result = property.nitems > 0 && property.value[0] != '\0';
	XFree(property.value);
	return result;
}

static Window
transient_parent(Window window)
{
	Window parent = None;
	XGetTransientForHint(display, window, &parent);
	return parent;
}

static long
distance_squared(const XWindowAttributes *left, const XWindowAttributes *right)
{
	long left_x = left->x + left->width / 2;
	long left_y = left->y + left->height / 2;
	long right_x = right->x + right->width / 2;
	long right_y = right->y + right->height / 2;
	long dx = left_x - right_x;
	long dy = left_y - right_y;
	return dx * dx + dy * dy;
}

static Window
find_parent(Window chooser, unsigned long pid)
{
	Window returned_root;
	Window returned_parent;
	Window *children = NULL;
	unsigned int count = 0;
	Window best = None;
	long best_score = 0;
	XWindowAttributes chooser_attributes;
	unsigned int index;

	if (!XGetWindowAttributes(display, chooser, &chooser_attributes)
	    || !XQueryTree(display, root, &returned_root, &returned_parent,
	                   &children, &count))
		return None;
	for (index = 0; index < count; ++index) {
		Window candidate = children[index];
		unsigned long candidate_pid;
		XWindowAttributes attributes;
		long score;

		if (candidate == chooser || !is_mapped(candidate)
		    || !is_wps_class(candidate)
		    || !get_cardinal(candidate, atom_pid, &candidate_pid)
		    || candidate_pid != pid
		    || !XGetWindowAttributes(display, candidate, &attributes))
			continue;
		if (!has_legacy_name(candidate) && !is_dialog(candidate)
		    && transient_parent(candidate) == None)
			continue;
		score = distance_squared(&chooser_attributes, &attributes);
		if (is_dialog(candidate) || transient_parent(candidate) != None)
			score /= 4;
		/* Later XQueryTree entries are higher in the stack. */
		if (best == None || score <= best_score) {
			best = candidate;
			best_score = score;
		}
	}
	if (children)
		XFree(children);
	return best;
}

static bool
looks_like_broken_chooser(Window window, unsigned long *pid)
{
	XWindowAttributes attributes;
	Window parent;

	if (!is_mapped(window) || !is_wps_class(window)
	    || !get_cardinal(window, atom_pid, pid) || has_legacy_name(window)
	    || is_dialog(window))
		return false;
	parent = transient_parent(window);
	if (parent != None || !XGetWindowAttributes(display, window, &attributes))
		return false;
	/*
	 * WPS can create its unnamed native chooser at almost the full X screen
	 * size. Its missing name/type/transient properties are the useful
	 * signature; rejecting it at 95% leaves it below the dialog that opened it.
	 */
	return attributes.width >= 320 && attributes.height >= 180
	       && attributes.width <= DisplayWidth(display, DefaultScreen(display))
	       && attributes.height <= DisplayHeight(display, DefaultScreen(display));
}

static bool
repair_window(Window window)
{
	unsigned long pid;
	Window parent;

	/* Normal dialogs already have a valid stacking relationship. Raising every
	 * MapNotify can put their parent back above a newly opened file chooser. */
	if (transient_parent(window) != None || is_dialog(window))
		return false;
	if (!looks_like_broken_chooser(window, &pid))
		return false;
	parent = find_parent(window, pid);
	if (parent == None)
		return false;

	XUnmapWindow(display, window);
	XSetTransientForHint(display, window, parent);
	XChangeProperty(display, window, atom_window_type, XA_ATOM, 32,
	                PropModeReplace, (unsigned char *)&atom_dialog, 1);
	XMapRaised(display, window);
	XSetInputFocus(display, window, RevertToPointerRoot, CurrentTime);
	XFlush(display);
	log_line("repaired chooser=0x%lx parent=0x%lx pid=%lu",
	         window, parent, pid);
	return true;
}

static void
repair_existing(void)
{
	Window returned_root;
	Window returned_parent;
	Window *children = NULL;
	unsigned int count = 0;
	unsigned int index;

	if (!XQueryTree(display, root, &returned_root, &returned_parent,
	                &children, &count))
		return;
	for (index = 0; index < count; ++index)
		repair_window(children[index]);
	if (children)
		XFree(children);
}

static void
dump_windows(void)
{
	Window returned_root;
	Window returned_parent;
	Window *children = NULL;
	unsigned int count = 0;
	unsigned int index;

	if (!XQueryTree(display, root, &returned_root, &returned_parent,
	                &children, &count))
		return;
	for (index = 0; index < count; ++index) {
		Window window = children[index];
		unsigned long pid = 0;
		char class_name[64] = "?";
		Window parent = transient_parent(window);
		get_cardinal(window, atom_pid, &pid);
		get_class(window, class_name, sizeof class_name);
		printf("0x%lx mapped=%d class=%s pid=%lu transient=0x%lx dialog=%d name=%d\n",
		       window, is_mapped(window), class_name, pid, parent,
		       is_dialog(window), has_legacy_name(window));
	}
	if (children)
		XFree(children);
}

static int
acquire_lock(void)
{
	lock_fd = open(LOCK_PATH, O_CREAT | O_RDWR, 0600);
	if (lock_fd < 0)
		return -1;
	if (flock(lock_fd, LOCK_EX | LOCK_NB) < 0)
		return errno == EWOULDBLOCK ? 1 : -1;
	if (ftruncate(lock_fd, 0) == 0)
		dprintf(lock_fd, "%ld\n", (long)getpid());
	return 0;
}

static int
self_daemonize(void)
{
	pid_t pid;
	int null_fd;

	pid = fork();
	if (pid < 0)
		return -1;
	if (pid > 0)
		exit(EXIT_SUCCESS);
	if (setsid() < 0)
		return -1;
	pid = fork();
	if (pid < 0)
		return -1;
	if (pid > 0)
		exit(EXIT_SUCCESS);
	if (chdir("/") < 0)
		return -1;
	null_fd = open("/dev/null", O_RDWR);
	if (null_fd >= 0) {
		dup2(null_fd, STDIN_FILENO);
		dup2(null_fd, STDOUT_FILENO);
		dup2(null_fd, STDERR_FILENO);
		if (null_fd > STDERR_FILENO)
			close(null_fd);
	}
	return 0;
}

int
main(int argc, char **argv)
{
	bool dump = argc == 2 && strcmp(argv[1], "--dump") == 0;
	int lock_result;
	XEvent event;

	display = XOpenDisplay(NULL);
	if (!display) {
		fprintf(stderr, "nobrain-dialog-raise: cannot open DISPLAY\n");
		return EXIT_FAILURE;
	}
	root = DefaultRootWindow(display);
	XSetErrorHandler(handle_x_error);
	atom_pid = XInternAtom(display, "_NET_WM_PID", False);
	atom_window_type = XInternAtom(display, "_NET_WM_WINDOW_TYPE", False);
	atom_dialog = XInternAtom(display, "_NET_WM_WINDOW_TYPE_DIALOG", False);

	if (dump) {
		dump_windows();
		XCloseDisplay(display);
		return EXIT_SUCCESS;
	}
	if (argc != 1) {
		fprintf(stderr, "usage: %s [--dump]\n", argv[0]);
		XCloseDisplay(display);
		return EXIT_FAILURE;
	}
	lock_result = acquire_lock();
	if (lock_result == 1) {
		XCloseDisplay(display);
		return EXIT_SUCCESS;
	}
	if (lock_result < 0 || self_daemonize() < 0) {
		perror("nobrain-dialog-raise");
		XCloseDisplay(display);
		return EXIT_FAILURE;
	}

	signal(SIGINT, stop_running);
	signal(SIGTERM, stop_running);
	signal(SIGHUP, stop_running);
	XSelectInput(display, root, SubstructureNotifyMask);
	repair_existing();
	log_line("started pid=%ld", (long)getpid());

	while (running) {
		if (XPending(display) == 0) {
			usleep(50000);
			continue;
		}
		XNextEvent(display, &event);
		if (event.type == MapNotify) {
			/* WPS may publish properties immediately after mapping. */
			usleep(100000);
			repair_window(event.xmap.window);
		}
	}
	log_line("stopped pid=%ld", (long)getpid());
	XCloseDisplay(display);
	if (lock_fd >= 0)
		close(lock_fd);
	return EXIT_SUCCESS;
}
