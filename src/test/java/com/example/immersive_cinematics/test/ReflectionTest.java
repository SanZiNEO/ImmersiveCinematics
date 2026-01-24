import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import java.lang.reflect.Constructor;

public class ReflectionTest {
    public static void main(String[] args) {
        try {
            System.out.println("=== Minecraft 1.20.1 反射测试 ===");
            System.out.println("---");
            
            // 1. 检查 Minecraft 实例
            System.out.println("Minecraft 实例: " + Minecraft.getInstance());
            
            // 2. 检查 ClientPacketListener 类
            System.out.println("\n--- ClientPacketListener ---");
            printAllConstructors(ClientPacketListener.class);
            
            // 3. 检查 LocalPlayer 类
            System.out.println("\n--- LocalPlayer ---");
            printAllConstructors(LocalPlayer.class);
            
            System.out.println("\n--- 测试创建 ClientPacketListener ---");
            try {
                // 尝试创建一个简单的 ClientPacketListener 实例
                Constructor<?>[] cplConstructors = ClientPacketListener.class.getDeclaredConstructors();
                for (Constructor<?> ctor : cplConstructors) {
                    System.out.println("尝试构造函数: " + ctor);
                    try {
                        ctor.setAccessible(true);
                        
                        // 尝试创建实例
                        Object[] params = new Object[ctor.getParameterCount()];
                        // 简单的参数初始化
                        for (int i = 0; i < params.length; i++) {
                            params[i] = getDefaultValue(ctor.getParameterTypes()[i]);
                        }
                        
                        ClientPacketListener listener = (ClientPacketListener) ctor.newInstance(params);
                        System.out.println("✅ 创建成功: " + listener);
                        break;
                    } catch (Exception e) {
                        System.out.println("❌ 失败: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ 测试 ClientPacketListener 失败: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.out.println("\n❌ 测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printAllConstructors(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        System.out.println("构造函数数量: " + constructors.length);
        
        for (int i = 0; i < constructors.length; i++) {
            Constructor<?> ctor = constructors[i];
            System.out.println("构造函数 " + (i+1) + ": " + ctor);
            System.out.println("  参数类型:");
            
            Class<?>[] paramTypes = ctor.getParameterTypes();
            for (int j = 0; j < paramTypes.length; j++) {
                System.out.println("    " + j + ": " + paramTypes[j].getName());
            }
            System.out.println("  访问修饰符: " + (ctor.isAccessible() ? "public" : "private"));
        }
    }
    
    private static Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        if (type == String.class) return "";
        if (type == Minecraft.class) return Minecraft.getInstance();
        if (type == Connection.class) return new Connection(PacketFlow.CLIENTBOUND);
        if (type == GameProfile.class) return new GameProfile(java.util.UUID.randomUUID(), "TestPlayer");
        return null;
    }
}