/*
 * wp_fractional_scale_manager_v1 (version 1): the preferred scale of a surface, as a numerator
 * over 120. Wine's winewayland.drv binds it at start-up and logs "Wayland compositor doesn't
 * support wp_fractional_scale_manager_v1 (fractional scaling will be broken)" when it is missing.
 *
 * The scene is shown 1:1 to every client (wl_output reports scale 1, the screen fit/centre step
 * in compositor.c scales the whole desktop afterwards), so the scale sent is always 120/120 = 1.0.
 * A client that renders at that scale draws buffers the size of its surface, as it did without
 * the global; only the error and the missing-protocol fallback inside Wine go away.
 */
#include <stdlib.h>
#include "droiddeck_ext.h"
#include "fractional-scale-v1-server-protocol.h"

#define FRACTIONAL_SCALE_ONE 120u

struct frac_scale {
    struct wl_resource *surface;   /* NULL once the surface is gone: the object stays, inert */
    struct wl_resource *resource;
    struct wl_listener surface_destroy;
    struct wl_list link;
};

static struct wl_list g_frac_scales;   /* struct frac_scale.link, every live object */

static void fs_surface_destroyed(struct wl_listener *l, void *data) {
    struct frac_scale *f = wl_container_of(l, f, surface_destroy);
    wl_list_remove(&f->surface_destroy.link);
    wl_list_init(&f->surface_destroy.link);
    f->surface = NULL;
}

static void fs_resource_destroyed(struct wl_resource *r) {
    struct frac_scale *f = wl_resource_get_user_data(r);
    if (!f) return;
    wl_list_remove(&f->surface_destroy.link);
    wl_list_remove(&f->link);
    free(f);
}

static void fs_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wp_fractional_scale_v1_interface fs_impl = { .destroy = fs_destroy };

static void mgr_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void mgr_get_fractional_scale(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                     struct wl_resource *surface) {
    struct frac_scale *f;
    wl_list_for_each(f, &g_frac_scales, link) {
        if (f->surface == surface) {
            wl_resource_post_error(r, WP_FRACTIONAL_SCALE_MANAGER_V1_ERROR_FRACTIONAL_SCALE_EXISTS,
                                   "the surface already has a wp_fractional_scale_v1 object");
            return;
        }
    }
    f = calloc(1, sizeof(*f));
    struct wl_resource *res = f ? wl_resource_create(c, &wp_fractional_scale_v1_interface,
                                                     wl_resource_get_version(r), id) : NULL;
    if (!res) { free(f); wl_client_post_no_memory(c); return; }
    f->surface = surface;
    f->resource = res;
    f->surface_destroy.notify = fs_surface_destroyed;
    wl_resource_add_destroy_listener(surface, &f->surface_destroy);
    wl_list_insert(&g_frac_scales, &f->link);
    wl_resource_set_implementation(res, &fs_impl, f, fs_resource_destroyed);
    /* Sent at once: Wine applies the scale before its first commit. */
    wp_fractional_scale_v1_send_preferred_scale(res, FRACTIONAL_SCALE_ONE);
}

static const struct wp_fractional_scale_manager_v1_interface mgr_impl = {
    .destroy = mgr_destroy, .get_fractional_scale = mgr_get_fractional_scale,
};

static void bind_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_fractional_scale_manager_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &mgr_impl, NULL, NULL);
}

void fractional_scale_init(struct wl_display *display) {
    wl_list_init(&g_frac_scales);
    wl_global_create(display, &wp_fractional_scale_manager_v1_interface, 1, NULL, bind_manager);
}
