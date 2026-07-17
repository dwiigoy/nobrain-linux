/*
 * xkbcomp's KeyBind.c and XKBGeom.c (whole-archive linked into Xlorie via
 * the "xkbcomp" static lib) pull in a handful of Xlib/XKB *client-side*
 * request/reply helpers from XlibInt.c and XKBRdBuf.c, which are not part
 * of this build. Xlorie is an X server, not an Xlib client -- the
 * functions that call these (XkbGetGeometry, XkbGetNamedGeometry,
 * _XKeyInitialize, XRefreshKeyboardMapping) are never reached.
 *
 * These stubs exist solely to satisfy -Wl,--no-undefined for that dead
 * code; none of them are ever called.
 */

#include <stddef.h>

typedef int Bool;
typedef unsigned long XID;
typedef XID KeySym;
typedef unsigned long KeyCode;
typedef struct _XDisplay Display;
typedef struct _XLockRec *LockInfoPtr;
typedef struct _xReply xReply;
typedef struct _XModifierKeymap XModifierKeymap;

typedef struct _XkbReadBuffer {
    int   error;
    int   size;
    char *start;
    char *data;
} XkbReadBufferRec, *XkbReadBufferPtr;

void (*_XFreeMutex_fn)(LockInfoPtr lock) = NULL;

void *_XGetRequest(Display *dpy, unsigned char type, size_t len)
{
    return NULL;
}

int _XReply(Display *dpy, xReply *rep, int extra, Bool discard)
{
    return 0;
}

XModifierKeymap *XGetModifierMapping(Display *dpy)
{
    return NULL;
}

int XFreeModifiermap(XModifierKeymap *modmap)
{
    return 0;
}

KeySym *XGetKeyboardMapping(Display *dpy, KeyCode first, int count, int *n)
{
    return NULL;
}

Bool XkbUseExtension(Display *dpy, int *major_rtrn, int *minor_rtrn)
{
    return 0;
}

int _XkbInitReadBuffer(Display *dpy, XkbReadBufferPtr buf, int size)
{
    return 0;
}

int _XkbSkipReadBufferData(XkbReadBufferPtr from, int size)
{
    return 0;
}

int _XkbCopyFromReadBuffer(XkbReadBufferPtr from, char *to, int size)
{
    return 0;
}

char *_XkbGetReadBufferPtr(XkbReadBufferPtr from, int size)
{
    return NULL;
}

int _XkbFreeReadBuffer(XkbReadBufferPtr buf)
{
    return 0;
}

Bool _XkbGetReadBufferCountedString(XkbReadBufferPtr buf, char **rtrn)
{
    return 0;
}
