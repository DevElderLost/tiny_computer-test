/*
 * zxdg_output_manager_v1 (version 3): the logical position and size of the output, which Wine's
 * winewayland.drv asks for next to wl_output. The scene is shown 1:1, so the logical size is the
 * output's own mode and the position is 0,0 (a single output). The size follows output_resized().
 */
#include <stdlib.h>
#include "droiddeck_ext.h"
#include "xdg-output-unstable-v1-server-protocol.h"

extern volatile int g_output_w, g_output_h;   /* compositor.c */

struct xo {
    struct wl_resource *res;
    struct wl_list link;
};

static struct wl_list g_xos;                  /* struct xo.link, every live zxdg_output_v1 */

static void xo_size(struct wl_resource *r) {
    zxdg_output_v1_send_logical_size(r, g_output_w > 0 ? g_output_w : 1920,
                                     g_output_h > 0 ? g_output_h : 1080);
}

static void xo_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zxdg_output_v1_interface xo_impl = { .destroy = xo_destroy };

static void xo_resource_destroyed(struct wl_resource *r) {
    struct xo *x = wl_resource_get_user_data(r);
    if (!x) return;
    wl_list_remove(&x->link);
    free(x);
}

static void mgr_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void mgr_get_xdg_output(struct wl_client *c, struct wl_resource *r, uint32_t id,
                               struct wl_resource *output) {
    struct xo *x = calloc(1, sizeof(*x));
    struct wl_resource *res = x ? wl_resource_create(c, &zxdg_output_v1_interface,
                                                     wl_resource_get_version(r), id) : NULL;
    if (!res) { free(x); wl_client_post_no_memory(c); return; }
    x->res = res;
    wl_list_insert(&g_xos, &x->link);
    wl_resource_set_implementation(res, &xo_impl, x, xo_resource_destroyed);
    zxdg_output_v1_send_logical_position(res, 0, 0);
    xo_size(res);
    if (wl_resource_get_version(res) >= ZXDG_OUTPUT_V1_NAME_SINCE_VERSION) {
        zxdg_output_v1_send_name(res, "DroidDeck-1");
        zxdg_output_v1_send_description(res, "DroidDeck display");
    }
    /* From version 3 the wl_output.done ends the set; before it, xdg_output.done does. */
    if (wl_resource_get_version(res) < 3) zxdg_output_v1_send_done(res);
    else if (output && wl_resource_get_version(output) >= 2) wl_output_send_done(output);
}

static const struct zxdg_output_manager_v1_interface mgr_impl = {
    .destroy = mgr_destroy, .get_xdg_output = mgr_get_xdg_output,
};

static void bind_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zxdg_output_manager_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &mgr_impl, NULL, NULL);
}

/* The output's size changed: every xdg_output gets the new logical size (compositor.c then sends
 * wl_output.done, which commits it for version 3 clients). */
void xdg_output_resized(void) {
    struct xo *x;
    wl_list_for_each(x, &g_xos, link) {
        xo_size(x->res);
        if (wl_resource_get_version(x->res) < 3) zxdg_output_v1_send_done(x->res);
    }
}

void xdg_output_init(struct wl_display *display) {
    wl_list_init(&g_xos);
    wl_global_create(display, &zxdg_output_manager_v1_interface, 3, NULL, bind_manager);
}
