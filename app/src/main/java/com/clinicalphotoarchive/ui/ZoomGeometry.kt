package com.clinicalphotoarchive.ui

import kotlin.math.max

data class ZoomState(val scale: Float=1f,val offsetX: Float=0f,val offsetY: Float=0f)
data class ZoomBounds(val viewportW: Float,val viewportH: Float,val fittedW: Float,val fittedH: Float)

object ZoomGeometry {
    fun transform(state: ZoomState,zoom: Float,panX: Float,panY: Float,centroidX: Float,centroidY: Float,bounds: ZoomBounds): ZoomState {
        if(!zoom.isFinite() || zoom<=0 || bounds.viewportW<=0 || bounds.viewportH<=0) return state
        val scale=(state.scale*zoom).coerceIn(1f,5f)
        if(scale==1f) return ZoomState()
        val ratio=scale/state.scale
        val x=state.offsetX*ratio+(centroidX-bounds.viewportW/2)*(1-ratio)+panX
        val y=state.offsetY*ratio+(centroidY-bounds.viewportH/2)*(1-ratio)+panY
        val maxX=max(0f,(bounds.fittedW*scale-bounds.viewportW)/2)
        val maxY=max(0f,(bounds.fittedH*scale-bounds.viewportH)/2)
        return ZoomState(scale,x.coerceIn(-maxX,maxX),y.coerceIn(-maxY,maxY))
    }
    fun doubleTap(state: ZoomState,x: Float,y: Float,bounds: ZoomBounds): ZoomState =
        if(state.scale>1f) ZoomState() else transform(state,2.5f,0f,0f,x,y,bounds)
}
