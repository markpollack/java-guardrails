package fx;

public class Plain { private final Object lock = new Object(); synchronized int one() { return 1; } }
