package com.clinicalphotoarchive.ui

import org.junit.Assert.*
import org.junit.Test

class ZoomGeometryTest {
    private val bounds=ZoomBounds(400f,300f,400f,200f)
    @Test fun preservesPointUnderPinchCentroid() {
        val s=ZoomGeometry.transform(ZoomState(),2f,0f,0f,300f,150f,bounds)
        assertEquals(2f,s.scale,0.001f)
        assertEquals(-100f,s.offsetX,0.001f)
        assertEquals(0f,s.offsetY,0.001f)
    }
    @Test fun clampsPanToFittedImageAndScaleToFive() {
        val s=ZoomGeometry.transform(ZoomState(),20f,10000f,-10000f,200f,150f,bounds)
        assertEquals(5f,s.scale,0.001f)
        assertEquals(800f,s.offsetX,0.001f)
        assertEquals(-350f,s.offsetY,0.001f)
    }
    @Test fun resetsPanWhenZoomReturnsToOne() {
        val s=ZoomGeometry.transform(ZoomState(3f,100f,80f),0.1f,10f,20f,100f,100f,bounds)
        assertEquals(ZoomState(),s)
    }
    @Test fun doubleTapZoomsAroundTappedPointThenResets() {
        val s=ZoomGeometry.doubleTap(ZoomState(),300f,150f,bounds)
        assertEquals(2.5f,s.scale,0.001f);assertEquals(-150f,s.offsetX,0.001f)
        assertEquals(ZoomState(),ZoomGeometry.doubleTap(s,300f,150f,bounds))
    }
    @Test fun smallPortraitImageDoesNotPanAlongEmptyAxis() {
        val s=ZoomGeometry.transform(ZoomState(),2f,40f,40f,200f,150f,ZoomBounds(400f,300f,100f,300f))
        assertEquals(0f,s.offsetX,0.001f);assertEquals(40f,s.offsetY,0.001f)
    }
}
