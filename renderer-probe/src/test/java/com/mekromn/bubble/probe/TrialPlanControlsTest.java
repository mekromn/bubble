package com.mekromn.bubble.probe;

import org.junit.Test;
import org.json.JSONObject;
import java.util.List;
import static org.junit.Assert.*;

public class TrialPlanControlsTest {
    private JSONObject variant(String name) { return TrialPlan.spec(name,false,"apz",0,115); }
    @Test public void poolAndDrainAreIndependentControls() {
        JSONObject baseline=variant("relay_latest_no_bp"), small=variant("relay_small_pool");
        JSONObject drainOnly=variant("relay_pool6_drain2"), poolOnly=variant("relay_pool4_drain4");
        assertEquals(6,baseline.optInt("maxImages")); assertEquals(4,baseline.optInt("drainLimit"));
        assertEquals(4,small.optInt("maxImages")); assertEquals(2,small.optInt("drainLimit"));
        assertEquals(6,drainOnly.optInt("maxImages")); assertEquals(2,drainOnly.optInt("drainLimit"));
        assertEquals(4,poolOnly.optInt("maxImages")); assertEquals(4,poolOnly.optInt("drainLimit"));
        for(JSONObject x:List.of(baseline,small,drainOnly,poolOnly))assertFalse(x.optBoolean("backpressure"));
    }
    @Test public void everyVariantHasBothHosts() {
        List<JSONObject> plan=TrialPlan.matrix(1,false,115);
        assertEquals(22,plan.size());
        for(String v:TrialPlan.VARIANTS)for(boolean floating:new boolean[]{false,true}) {
            long count=plan.stream().filter(x->v.equals(x.optString("variant"))&&floating==x.optBoolean("floating")).count();
            assertEquals(1,count);
        }
    }
}
