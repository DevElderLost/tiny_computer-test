/*
 * Server-side glue for wlr-layer-shell-unstable-v1 (protocols/wlr-layer-shell-unstable-v1.xml).
 * Written by hand in the shape wayland-scanner emits, because the build has no scanner. To replace
 * it with the real thing:
 *   wayland-scanner server-header protocols/wlr-layer-shell-unstable-v1.xml \
 *       generated/wlr-layer-shell-unstable-v1-server-protocol.h
 *   wayland-scanner private-code  protocols/wlr-layer-shell-unstable-v1.xml \
 *       generated/wlr-layer-shell-unstable-v1-protocol.c
 *
 * Copyright © 2017 Drew DeVault (protocol); see the XML for the licence.
 */

#ifndef WLR_LAYER_SHELL_UNSTABLE_V1_SERVER_PROTOCOL_H
#define WLR_LAYER_SHELL_UNSTABLE_V1_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

struct wl_output;
struct wl_surface;
struct xdg_popup;
struct zwlr_layer_shell_v1;
struct zwlr_layer_surface_v1;

#ifndef ZWLR_LAYER_SHELL_V1_INTERFACE
#define ZWLR_LAYER_SHELL_V1_INTERFACE
extern const struct wl_interface zwlr_layer_shell_v1_interface;
#endif
#ifndef ZWLR_LAYER_SURFACE_V1_INTERFACE
#define ZWLR_LAYER_SURFACE_V1_INTERFACE
extern const struct wl_interface zwlr_layer_surface_v1_interface;
#endif

#ifndef ZWLR_LAYER_SHELL_V1_ERROR_ENUM
#define ZWLR_LAYER_SHELL_V1_ERROR_ENUM
enum zwlr_layer_shell_v1_error {
	/** wl_surface has another role */
	ZWLR_LAYER_SHELL_V1_ERROR_ROLE = 0,
	/** layer value is invalid */
	ZWLR_LAYER_SHELL_V1_ERROR_INVALID_LAYER = 1,
	/** wl_surface has a buffer attached or committed */
	ZWLR_LAYER_SHELL_V1_ERROR_ALREADY_CONSTRUCTED = 2,
};
#endif /* ZWLR_LAYER_SHELL_V1_ERROR_ENUM */

#ifndef ZWLR_LAYER_SHELL_V1_LAYER_ENUM
#define ZWLR_LAYER_SHELL_V1_LAYER_ENUM
/** available layers for surfaces, bottom-most first */
enum zwlr_layer_shell_v1_layer {
	ZWLR_LAYER_SHELL_V1_LAYER_BACKGROUND = 0,
	ZWLR_LAYER_SHELL_V1_LAYER_BOTTOM = 1,
	ZWLR_LAYER_SHELL_V1_LAYER_TOP = 2,
	ZWLR_LAYER_SHELL_V1_LAYER_OVERLAY = 3,
};
#endif /* ZWLR_LAYER_SHELL_V1_LAYER_ENUM */

/**
 * @struct zwlr_layer_shell_v1_interface
 */
struct zwlr_layer_shell_v1_interface {
	/**
	 * create a layer_surface from a surface
	 *
	 * Create a layer surface for an existing surface. This assigns
	 * the role of layer_surface, or raises a protocol error if another
	 * role is already assigned.
	 * @param layer layer to add this surface to
	 * @param namespace_ namespace for the layer surface
	 */
	void (*get_layer_surface)(struct wl_client *client,
				  struct wl_resource *resource,
				  uint32_t id,
				  struct wl_resource *surface,
				  struct wl_resource *output,
				  uint32_t layer,
				  const char *namespace_);
	/**
	 * destroy the layer_shell object
	 *
	 * Objects created through this instance are not affected.
	 * @since 3
	 */
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWLR_LAYER_SHELL_V1_GET_LAYER_SURFACE_SINCE_VERSION 1
#define ZWLR_LAYER_SHELL_V1_DESTROY_SINCE_VERSION 3

#ifndef ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ENUM
#define ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ENUM
/** types of keyboard interaction possible for a layer shell surface */
enum zwlr_layer_surface_v1_keyboard_interactivity {
	/** no keyboard focus is possible */
	ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_NONE = 0,
	/** request exclusive keyboard focus */
	ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_EXCLUSIVE = 1,
	/** request regular keyboard focus semantics */
	ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ON_DEMAND = 2,
};
/** @ingroup iface_zwlr_layer_surface_v1 */
#define ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ON_DEMAND_SINCE_VERSION 4
#endif /* ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ENUM */

#ifndef ZWLR_LAYER_SURFACE_V1_ERROR_ENUM
#define ZWLR_LAYER_SURFACE_V1_ERROR_ENUM
enum zwlr_layer_surface_v1_error {
	/** provided surface state is invalid */
	ZWLR_LAYER_SURFACE_V1_ERROR_INVALID_SURFACE_STATE = 0,
	/** size is invalid */
	ZWLR_LAYER_SURFACE_V1_ERROR_INVALID_SIZE = 1,
	/** anchor bitfield is invalid */
	ZWLR_LAYER_SURFACE_V1_ERROR_INVALID_ANCHOR = 2,
	/** keyboard interactivity is invalid */
	ZWLR_LAYER_SURFACE_V1_ERROR_INVALID_KEYBOARD_INTERACTIVITY = 3,
	/** exclusive edge is invalid given the surface anchors */
	ZWLR_LAYER_SURFACE_V1_ERROR_INVALID_EXCLUSIVE_EDGE = 4,
};
#endif /* ZWLR_LAYER_SURFACE_V1_ERROR_ENUM */

#ifndef ZWLR_LAYER_SURFACE_V1_ANCHOR_ENUM
#define ZWLR_LAYER_SURFACE_V1_ANCHOR_ENUM
enum zwlr_layer_surface_v1_anchor {
	/** the top edge of the anchor rectangle */
	ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP = 1,
	/** the bottom edge of the anchor rectangle */
	ZWLR_LAYER_SURFACE_V1_ANCHOR_BOTTOM = 2,
	/** the left edge of the anchor rectangle */
	ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT = 4,
	/** the right edge of the anchor rectangle */
	ZWLR_LAYER_SURFACE_V1_ANCHOR_RIGHT = 8,
};
#endif /* ZWLR_LAYER_SURFACE_V1_ANCHOR_ENUM */

/**
 * @struct zwlr_layer_surface_v1_interface
 */
struct zwlr_layer_surface_v1_interface {
	/** sets the size of the surface */
	void (*set_size)(struct wl_client *client,
			 struct wl_resource *resource,
			 uint32_t width,
			 uint32_t height);
	/** configures the anchor point of the surface */
	void (*set_anchor)(struct wl_client *client,
			   struct wl_resource *resource,
			   uint32_t anchor);
	/** configures the exclusive geometry of this surface */
	void (*set_exclusive_zone)(struct wl_client *client,
				   struct wl_resource *resource,
				   int32_t zone);
	/** sets a margin from the anchor point */
	void (*set_margin)(struct wl_client *client,
			   struct wl_resource *resource,
			   int32_t top,
			   int32_t right,
			   int32_t bottom,
			   int32_t left);
	/** requests keyboard events */
	void (*set_keyboard_interactivity)(struct wl_client *client,
					   struct wl_resource *resource,
					   uint32_t keyboard_interactivity);
	/** assign this layer_surface as an xdg_popup parent */
	void (*get_popup)(struct wl_client *client,
			  struct wl_resource *resource,
			  struct wl_resource *popup);
	/** ack a configure event */
	void (*ack_configure)(struct wl_client *client,
			      struct wl_resource *resource,
			      uint32_t serial);
	/** destroy the layer_surface */
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	/**
	 * change the layer of the surface
	 * @since 2
	 */
	void (*set_layer)(struct wl_client *client,
			  struct wl_resource *resource,
			  uint32_t layer);
	/**
	 * set the edge the exclusive zone will be applied to
	 * @since 5
	 */
	void (*set_exclusive_edge)(struct wl_client *client,
				   struct wl_resource *resource,
				   uint32_t edge);
};

#define ZWLR_LAYER_SURFACE_V1_CONFIGURE 0
#define ZWLR_LAYER_SURFACE_V1_CLOSED 1

#define ZWLR_LAYER_SURFACE_V1_CONFIGURE_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_CLOSED_SINCE_VERSION 1

#define ZWLR_LAYER_SURFACE_V1_SET_SIZE_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_SET_ANCHOR_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_SET_EXCLUSIVE_ZONE_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_SET_MARGIN_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_SET_KEYBOARD_INTERACTIVITY_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_GET_POPUP_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_ACK_CONFIGURE_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_DESTROY_SINCE_VERSION 1
#define ZWLR_LAYER_SURFACE_V1_SET_LAYER_SINCE_VERSION 2
#define ZWLR_LAYER_SURFACE_V1_SET_EXCLUSIVE_EDGE_SINCE_VERSION 5

/**
 * @ingroup iface_zwlr_layer_surface_v1
 * Sends a configure event to the client owning the resource.
 * @param resource_ The client's resource
 * @param serial serial for the configure
 */
static inline void
zwlr_layer_surface_v1_send_configure(struct wl_resource *resource_, uint32_t serial, uint32_t width, uint32_t height)
{
	wl_resource_post_event(resource_, ZWLR_LAYER_SURFACE_V1_CONFIGURE, serial, width, height);
}

/**
 * @ingroup iface_zwlr_layer_surface_v1
 * Sends a closed event to the client owning the resource.
 * @param resource_ The client's resource
 */
static inline void
zwlr_layer_surface_v1_send_closed(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWLR_LAYER_SURFACE_V1_CLOSED);
}

#ifdef  __cplusplus
}
#endif

#endif
