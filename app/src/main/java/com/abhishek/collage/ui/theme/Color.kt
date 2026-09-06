package com.abhishek.collage.ui.theme

import androidx.compose.ui.graphics.Color

// Warm, colour-blocked palette: a cream page with saturated blocks sitting on
// it, near-black type, and one accent per block.
//
// Magenta stays the primary because it is iykyk's own action colour, so the app
// still reads as belonging next to their product. The teal / amber / violet are
// supporting block colours, and each has a soft tint used for the block a chip
// sits in -- the saturated version is only ever used for the mark itself, which
// is what keeps a colourful screen from turning into noise.

val Cream = Color(0xFFFDF6EA)      // page ground
val CreamDeep = Color(0xFFF6EADA)  // recessed bands
val Card = Color(0xFFFFFFFF)       // raised cards
val Ink = Color(0xFF1F1218)        // near-black, warmed toward the ground
val InkSoft = Color(0xFF7A6470)    // secondary type

val Rose = Color(0xFFE11D63)       // iykyk magenta -- primary action
val RoseSoft = Color(0xFFFFDCE8)
val Teal = Color(0xFF2D7A68)
val TealSoft = Color(0xFFCFE8E1)
val Amber = Color(0xFFF0A92E)
val AmberSoft = Color(0xFFFFE9C2)
val Violet = Color(0xFF8285E6)
val VioletSoft = Color(0xFFE3E3FB)

/** Block colours in display order, cycled per person. */
val BlockColors = listOf(Rose, Teal, Amber, Violet)
val BlockSoftColors = listOf(RoseSoft, TealSoft, AmberSoft, VioletSoft)
