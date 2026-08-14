package tonton.pixelmonexpfixer;

import com.pixelmonmod.pixelmon.Pixelmon;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import tonton.pixelmonexpfixer.event.PixelmonEvents;

@Mod(Pixelmonexpfixer.MODID)
public class Pixelmonexpfixer {
    public static final String MODID = "pixelmonexpfixer";

    public Pixelmonexpfixer() {
        PixelmonEvents events = new PixelmonEvents();

        Pixelmon.EVENT_BUS.register(events);
        NeoForge.EVENT_BUS.register(events);
    }
}