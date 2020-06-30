package net.minecraft.launchwrapper;

import java.io.IOException;

@FunctionalInterface
public interface ClassDump {
    void dumpClass(String originalName, String finalName, byte[] bytes, Class<?> result) throws IOException;
}
