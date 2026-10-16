package com.example.orangeclient.module.render;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
public final class NoCameraClip extends Module {
    private final NumberSetting distance = add(new NumberSetting("Distance", 8.0, 1.0, 20.0, 0.5));
    private final BoolSetting frontView = add(new BoolSetting("DisableFrontView", false));
    public NoCameraClip() { super("NoCameraClip", Category.RENDER); }
    public float cameraDistance() { return (float) distance.get(); }
    public boolean disableFrontView() { return frontView.get(); }
}
