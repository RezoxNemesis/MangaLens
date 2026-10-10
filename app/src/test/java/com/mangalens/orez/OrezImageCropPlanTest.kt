package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN decode admission controls, not actual bitmap/SAF evidence. */
class OrezImageCropPlanTest {
    @Test fun fullLargeAndThinSourcesRequireBoundedActualDecode() {
        for((w,h) in listOf(100_000 to 100_000,3 to 100_000,4000 to 8000,1 to 1)) {
            val plan=OrezImageCropPlan.create(w,h,OrezImageCropRequest())
            val dw=((w.toLong()+plan.sample-1)/plan.sample).toInt();val dh=((h.toLong()+plan.sample-1)/plan.sample).toInt()
            assertTrue(plan.accepts(dw,dh));assertTrue(dw.toLong()*dh<=1_000_000);assertTrue(maxOf(dw,dh)<=1280)
            assertFalse(plan.accepts(dw+1,dh));assertFalse(plan.accepts(0,dh));assertEquals(0,plan.sample and (plan.sample-1))
        }
    }
    @Test fun explicitPercentRegionMapsOutwardToOriginalCoordinates() {
        val plan=OrezImageCropPlan.create(101,203,OrezImageCropRequest(10,20,80,90))
        assertEquals(OrezImageRegion(10,40,81,183),plan.region)
        assertEquals(1,plan.sample);assertTrue(plan.accepts(71,143))
    }
    @Test fun invalidOrReversedRegionAndSourceDimensionsAreRejected() {
        for(crop in listOf(OrezImageCropRequest(50,0,40,100),OrezImageCropRequest(0,100,100,100),
                OrezImageCropRequest(-1,0,100,100),OrezImageCropRequest(0,0,101,100)))
            assertTrue(runCatching { OrezImageCropPlan.create(4000,4000,crop) }.isFailure)
        for((w,h) in listOf(0 to 5,100_001 to 5,5 to -1))
            assertTrue(runCatching { OrezImageCropPlan.create(w,h,OrezImageCropRequest()) }.isFailure)
    }
}
