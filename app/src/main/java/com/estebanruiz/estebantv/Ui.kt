package com.estebanruiz.estebantv

import android.content.Context
import android.graphics.drawable.GradientDrawable

/** Utilidades de interfaz compartidas. */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun outline(fill: Int, stroke: Int, strokePx: Int, radiusPx: Float, oval: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(strokePx.coerceAtLeast(1), stroke)
            if (!oval) cornerRadius = radiusPx
        }

    fun filled(fill: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusPx
        }
}
