package com.mekromn.bubble.probe;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

final class TrialPlan {
    static JSONObject json(Object... values) {
        JSONObject object=new JSONObject();
        try { for(int i=0;i<values.length;i+=2)object.put((String)values[i],values[i+1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return object;
    }
    static JSONObject spec(String variant, boolean floating, String workload, int round, long seed) {
        boolean relay=variant.startsWith("relay_");
        return json("token",UUID.randomUUID().toString(),"variant",variant,"floating",floating,
            "workload",workload,"round",round,"seed",seed,"warmupMs",2500,"measureMs",10000,
            "renderer",relay?"relay":variant.equals("texture_max")?"texture":variant.equals("geckoview_max")?"geckoview":"raw",
            "voteMax",!variant.equals("raw_auto"),"extraWindow",variant.equals("raw_extra_window"),
            "maxImages",variant.equals("relay_small_pool")?4:6,
            "drainLimit",variant.equals("relay_fifo_bp")?1:variant.equals("relay_small_pool")?2:4,
            "backpressure",variant.equals("relay_fifo_bp")||variant.equals("relay_latest_bp"));
    }
    static List<JSONObject> quick(long seed) {
        List<JSONObject> out=new ArrayList<>();
        out.add(spec("geckoview_max",false,"apz",0,seed));
        out.add(spec("raw_max",false,"apz",0,seed));
        out.add(spec("raw_max",true,"apz",0,seed));
        out.add(spec("relay_latest_no_bp",true,"apz",0,seed));
        return out;
    }
    static List<JSONObject> matrix(int rounds, boolean bothWorkloads, long seed) {
        String[] variants={"raw_auto","raw_max","texture_max","raw_extra_window","relay_fifo_bp",
            "relay_latest_bp","relay_latest_no_bp","relay_small_pool"};
        List<JSONObject> out=new ArrayList<>();
        Random random=new Random(seed);
        for(int round=0;round<rounds;round++) for(String workload:bothWorkloads?new String[]{"apz","scroll","repaint"}:new String[]{"apz"}) {
            out.add(spec("geckoview_max",false,workload,round,seed));
            List<String> shuffled=new ArrayList<>(List.of(variants)); Collections.shuffle(shuffled,random);
            for(String variant:shuffled) {
                // Pair neighbours, flip AB/BA ordering across rounds, retain seed and order in reports.
                boolean firstFloating=((round+java.util.Arrays.asList(variants).indexOf(variant))%2)==1;
                out.add(spec(variant,firstFloating,workload,round,seed));
                out.add(spec(variant,!firstFloating,workload,round,seed));
            }
        }
        return out;
    }
}
