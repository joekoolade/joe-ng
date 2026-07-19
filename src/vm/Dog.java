package vm;

/** Overrides {@code Animal.sound()} — takes the same vtable slot (0) as the parent. */
public final class Dog extends Animal {
    @Override
    public int sound() {
        return 0x57;   // 'W'
    }
}
