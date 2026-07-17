/* See LICENSE file for copyright and license details. */

/* appearance */
static const unsigned int borderpx  = 1;        /* border pixel of windows */
static const unsigned int snap      = 32;       /* snap pixel */
static const int showbar            = 1;        /* 0 means no bar */
static const int topbar             = 1;        /* 0 means bottom bar */
static char font0[64]               = "monospace:size=10";
static char dmenufont[64]           = "monospace:size=10";
static const char *fonts[]          = { font0 };
static const char col_gray1[]       = "#222222";
static const char col_gray2[]       = "#444444";
static const char col_gray3[]       = "#bbbbbb";
static const char col_gray4[]       = "#eeeeee";
static const char col_cyan[]        = "#005577";
static const char *colors[][3]      = {
	/*               fg         bg         border   */
	[SchemeNorm] = { col_gray3, col_gray1, col_gray2 },
	[SchemeSel]  = { col_gray4, col_cyan,  col_cyan  },
};

/* tagging */
static const char *tags[] = { "1", "2", "3", "4", "5", "6", "7", "8", "9" };

/* No rules: every window opens on the current workspace */
static const Rule rules[] = {
	/* class      instance    title       tags mask  isfloating  monitor */
	{ "placehold", NULL,      NULL,       0,         0,          -1 },
	{ "nobrain-game", NULL,   NULL,       0,         1,          -1 },
};

/* layout(s) */
static const float mfact     = 0.55; /* factor of master area size [0.05..0.95] */
static const int nmaster     = 1;    /* number of clients in master area */
static const int resizehints = 0;    /* NoBrain tiling ignores app size hints so 1/4 slots work */
static const int lockfullscreen = 1; /* 1 will force focus on the fullscreen window */
static const unsigned int refreshrate = 60; /* monitor refresh rate in Hz */

static const Layout layouts[] = {
	/* symbol     arrange function */
	{ "[]=",      tile },    /* first entry is default */
	{ "><>",      NULL },    /* no layout function means floating behavior */
	{ "[M]",      monocle },
};

/* key definitions */
#define MODKEY Mod1Mask
#define TAGKEYS(KEY,TAG) \
	{ MODKEY,                       KEY, view,       {.ui = 1 << TAG} }, \
	{ MODKEY|ControlMask,           KEY, toggleview, {.ui = 1 << TAG} }, \
	{ MODKEY|ShiftMask,             KEY, tag,        {.ui = 1 << TAG} }, \
	{ MODKEY|ControlMask|ShiftMask, KEY, toggletag,  {.ui = 1 << TAG} },

/* helper for spawning shell commands in the pre dwm-5.0 fashion */
#define SHCMD(cmd) { .v = (const char*[]){ "/bin/sh", "-c", cmd, NULL } }

/* commands */
static char dmenumon[2] = "0"; /* component of dmenucmd, manipulated in spawn() */
static const char *dmenucmd[] = { "nobrain-menu", NULL };
static const char *termcmd[]  = { "nobrain-terminal", NULL };

static const Key keys[] = {
	/* modifier                     key        function        argument */
	{ MODKEY,                       XK_p,      spawn,          {.v = dmenucmd } },
	{ ControlMask|MODKEY,           XK_z,      spawn,          {.v = dmenucmd } },
	{ MODKEY|ShiftMask,             XK_Return, spawn,          {.v = termcmd } },
	{ ControlMask|MODKEY,           XK_t,      spawn,          {.v = termcmd } },
	{ MODKEY,                       XK_b,      togglebar,      {0} },
	{ MODKEY,                       XK_j,      focusstack,     {.i = +1 } },
	{ MODKEY,                       XK_k,      focusstack,     {.i = -1 } },
	{ MODKEY,                       XK_i,      incnmaster,     {.i = +1 } },
	{ MODKEY,                       XK_d,      incnmaster,     {.i = -1 } },
	{ MODKEY,                       XK_h,      setmfact,       {.f = -0.05} },
	{ MODKEY,                       XK_l,      setmfact,       {.f = +0.05} },
	{ MODKEY,                       XK_x,      togglenbresizemode, {0} },
	{ ControlMask|MODKEY,           XK_Up,     setnbslot,      {.i = NB_SLOT_TOP } },
	{ ControlMask|MODKEY,           XK_Down,   setnbslot,      {.i = NB_SLOT_BOTTOM } },
	{ ControlMask|MODKEY,           XK_Left,   setnbslot,      {.i = NB_SLOT_LEFT } },
	{ ControlMask|MODKEY,           XK_Right,  setnbslot,      {.i = NB_SLOT_RIGHT } },
	{ ControlMask|MODKEY,           XK_KP_Up,    setnbslot,    {.i = NB_SLOT_TOP } },
	{ ControlMask|MODKEY,           XK_KP_Down,  setnbslot,    {.i = NB_SLOT_BOTTOM } },
	{ ControlMask|MODKEY,           XK_KP_Left,  setnbslot,    {.i = NB_SLOT_LEFT } },
	{ ControlMask|MODKEY,           XK_KP_Right, setnbslot,    {.i = NB_SLOT_RIGHT } },
	{ ControlMask|MODKEY,           XK_Home,   setnbslot,      {.i = NB_SLOT_TOPLEFT } },
	{ ControlMask|MODKEY,           XK_Prior,  setnbslot,      {.i = NB_SLOT_TOPRIGHT } },
	{ ControlMask|MODKEY,           XK_End,    setnbslot,      {.i = NB_SLOT_BOTTOMLEFT } },
	{ ControlMask|MODKEY,           XK_Next,   setnbslot,      {.i = NB_SLOT_BOTTOMRIGHT } },
	{ ControlMask|MODKEY,           XK_KP_Home,  setnbslot,    {.i = NB_SLOT_TOPLEFT } },
	{ ControlMask|MODKEY,           XK_KP_Prior, setnbslot,    {.i = NB_SLOT_TOPRIGHT } },
	{ ControlMask|MODKEY,           XK_KP_End,   setnbslot,    {.i = NB_SLOT_BOTTOMLEFT } },
	{ ControlMask|MODKEY,           XK_KP_Next,  setnbslot,    {.i = NB_SLOT_BOTTOMRIGHT } },
	{ ControlMask|MODKEY,           XK_space,  setnbslot,      {.i = NB_SLOT_AUTO } },
	{ ControlMask|MODKEY,           XK_Return, setnbslot,      {.i = NB_SLOT_AUTO } },
	{ ControlMask|MODKEY,           XK_KP_Enter, setnbslot,    {.i = NB_SLOT_AUTO } },
	{ MODKEY,                       XK_Return, zoom,           {0} },
	{ MODKEY,                       XK_Tab,    view,           {0} },
	{ ControlMask,                  XK_Tab,    cycletag,       {.i = +1 } },
	{ ControlMask|ShiftMask,        XK_Tab,    cycletag,       {.i = -1 } },
	{ MODKEY,                       XK_Escape, killclient,     {0} },
	{ MODKEY|ShiftMask,             XK_c,      killclient,     {0} },
	{ MODKEY|ShiftMask,             XK_t,      setlayout,      {.v = &layouts[0]} },
	{ MODKEY,                       XK_g,      setlayout,      {.v = &layouts[0]} },
	{ MODKEY,                       XK_f,      setlayout,      {.v = &layouts[1]} },
	{ MODKEY,                       XK_m,      setlayout,      {.v = &layouts[2]} },
	{ MODKEY,                       XK_space,  setlayout,      {0} },
	{ MODKEY|ShiftMask,             XK_space,  togglefloating, {0} },
	{ MODKEY,                       XK_0,      view,           {.ui = ~0 } },
	{ MODKEY|ShiftMask,             XK_0,      tag,            {.ui = ~0 } },
	{ MODKEY,                       XK_comma,  focusmon,       {.i = -1 } },
	{ MODKEY,                       XK_period, focusmon,       {.i = +1 } },
	{ MODKEY|ShiftMask,             XK_comma,  tagmon,         {.i = -1 } },
	{ MODKEY|ShiftMask,             XK_period, tagmon,         {.i = +1 } },
	TAGKEYS(                        XK_1,                      0)
	TAGKEYS(                        XK_2,                      1)
	TAGKEYS(                        XK_3,                      2)
	TAGKEYS(                        XK_4,                      3)
	TAGKEYS(                        XK_5,                      4)
	TAGKEYS(                        XK_6,                      5)
	TAGKEYS(                        XK_7,                      6)
	TAGKEYS(                        XK_8,                      7)
	TAGKEYS(                        XK_9,                      8)
	{ MODKEY|ShiftMask,             XK_q,      quit,           {0} },
};

/* button definitions */
/* click can be ClkTagBar, ClkLtSymbol, ClkStatusText, ClkWinTitle, ClkClientWin, or ClkRootWin */
static const Button buttons[] = {
	/* click                event mask      button          function        argument */
	{ ClkLtSymbol,          0,              Button1,        setlayout,      {0} },
	{ ClkLtSymbol,          0,              Button3,        setlayout,      {.v = &layouts[2]} },
	{ ClkWinTitle,          0,              Button2,        zoom,           {0} },
	{ ClkStatusText,        0,              Button2,        spawn,          {.v = termcmd } },
	{ ClkClientWin,         MODKEY,         Button1,        movemouse,      {0} },
	{ ClkClientWin,         MODKEY,         Button2,        togglefloating, {0} },
	{ ClkClientWin,         MODKEY,         Button3,        resizemouse,    {0} },
	{ ClkTagBar,            0,              Button1,        view,           {.ui = 1} },
	{ ClkTagBar,            0,              Button3,        toggleview,     {0} },
	{ ClkTagBar,            MODKEY,         Button1,        tag,            {0} },
	{ ClkTagBar,            MODKEY,         Button3,        toggletag,      {0} },
};
