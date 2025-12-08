package com.mpatric.mp3agic;

/**
 * A mutable integer wrapper class that allows modification of the integer value.
 * Used primarily for counting occurrences in collections.
 */
public class MutableInteger {

    private int value;

    /**
     * Constructs a MutableInteger with the specified initial value.
     *
     * @param value the initial value
     */
    public MutableInteger(int value) {
        this.value = value;
    }

    /**
     * Increments the integer value by 1.
     */
    public void increment() {
        value++;
    }

    /**
     * Returns the current integer value.
     *
     * @return the current value
     */
    public int getValue() {
        return value;
    }

    /**
     * Sets the integer value to the specified value.
     *
     * @param value the new value to set
     */
    public void setValue(int value) {
        this.value = value;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + value;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        MutableInteger other = (MutableInteger) obj;
        if (value != other.value) return false;
        return true;
    }
}
