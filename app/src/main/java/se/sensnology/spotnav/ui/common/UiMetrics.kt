package se.sensnology.spotnav.ui.common

// Sizes and colours that more than one screen part draws with.

// Alpha of the shading over the target track's dead ends.
internal const val SHADING_ALPHA = 0x99

// The attention colour.
internal val ATTENTION_COLOUR = 0xFFD47A19.toInt()

// How thick every slider's track is drawn.
internal const val SLIDER_TRACK_DP = 10

// The room under a slider's heading, before its track.
internal const val TRACK_TOP_DP = 8

// Top margin of the slot the two drivers share, so switching drivers cannot move the value.
internal const val DRIVER_SLOT_TOP_DP = 16

// Bottom room in the slot: the thumb's pressed halo is drawn outside the slider's box.
internal const val DRIVER_SLOT_BOTTOM_DP = 6

// Room below a card header that ends in a control rather than title text.
internal const val HEADER_CONTROL_GAP_DP = 10

// The charging mark's rail width and segment gap.
internal const val CHARGING_RAIL_DP = 3
internal const val CHARGING_SEGMENT_GAP_DP = 2

// Minimum touch target around a header icon (see ActionTarget and `expandActionTarget`).
internal const val ACTION_TARGET_DP = 48

// A shape with no stated width spans its layer.
internal const val NO_INTRINSIC_WIDTH = -1

// Gap TargetShading aims for between the target slider's two end labels.
internal const val LABEL_GAP_DP = 6
