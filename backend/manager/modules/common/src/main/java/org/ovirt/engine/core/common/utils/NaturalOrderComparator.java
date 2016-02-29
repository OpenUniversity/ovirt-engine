package org.ovirt.engine.core.common.utils;

import java.io.Serializable;
import java.util.Comparator;

/**
 * A comparator that compares according to a {@link Comparable}'s natural ordering.
 *
 */
public class NaturalOrderComparator<T extends Comparable<T>> implements Comparator<T>, Serializable {
    private static final long serialVersionUID = -6526893568738803369L;

    @Override
    public int compare(T o1, T o2) {
        return o1.compareTo(o2);
    }
}
