import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import java.lang.reflect.Constructor;

public class SimpleConstructorTest {
    public static void main(String[] args) {
        try {
            System.out.println("=== Minecraft 1.20.1 Class Information ===");
            System.out.println("---");
            
            // 检查 Minecraft 类是否可访问
            System.out.println("Minecraft class: " + Minecraft.class);
            
            // 检查 ClientPacketListener 类
            System.out.println("\n=== ClientPacketListener ===");
            printConstructorInfo(ClientPacketListener.class);
            
            // 检查 LocalPlayer 类
            System.out.println("\n=== LocalPlayer ===");
            printConstructorInfo(LocalPlayer.class);
            
            System.out.println("\n---");
            System.out.println("Test completed successfully!");
            
        } catch (Exception e) {
            System.out.println("\nERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printConstructorInfo(Class<?> clazz) {
        try {
            Constructor<?>[] constructors = clazz.getConstructors();
            
            if (constructors.length == 0) {
                System.out.println("No public constructors found");
                return;
            }
            
            for (int i = 0; i < constructors.length; i++) {
                System.out.println("\nConstructor " + (i + 1) + ": " + constructors[i]);
                System.out.println("  Parameter count: " + constructors[i].getParameterCount());
                
                System.out.println("  Parameters:");
                Class<?>[] paramTypes = constructors[i].getParameterTypes();
                for (int j = 0; j < paramTypes.length; j++) {
                    System.out.println("    " + j + ": " + paramTypes[j].getName());
                }
            }
        } catch (Exception e) {
            System.out.println("Error accessing class: " + e.getMessage());
        }
    }
}