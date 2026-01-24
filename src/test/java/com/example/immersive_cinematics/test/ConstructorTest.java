import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import java.lang.reflect.Constructor;

public class ConstructorTest {
    public static void main(String[] args) {
        try {
            System.out.println("=== ClientPacketListener 构造函数 ===");
            Constructor<?>[] packetConstructors = ClientPacketListener.class.getConstructors();
            for (int i = 0; i < packetConstructors.length; i++) {
                System.out.println("\n构造函数 " + (i + 1) + ":");
                System.out.println("  参数类型:");
                Class<?>[] paramTypes = packetConstructors[i].getParameterTypes();
                for (Class<?> paramType : paramTypes) {
                    System.out.println("    - " + paramType.getName());
                }
            }
            
            System.out.println("\n=== LocalPlayer 构造函数 ===");
            Constructor<?>[] playerConstructors = LocalPlayer.class.getConstructors();
            for (int i = 0; i < playerConstructors.length; i++) {
                System.out.println("\n构造函数 " + (i + 1) + ":");
                System.out.println("  参数类型:");
                Class<?>[] paramTypes = playerConstructors[i].getParameterTypes();
                for (Class<?> paramType : paramTypes) {
                    System.out.println("    - " + paramType.getName());
                }
            }
            
        } catch (Exception e) {
            System.out.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}