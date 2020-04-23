package com.devebot.opflow.supports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 *
 * @author acegik
 */
public class OpflowCollectionUtil {
    
    public static String[] mergeArrays(final String[] ... arrs) {
        int length = 0;
        for(String[] arr : arrs) {
            if (arr != null) {
                length += arr.length;
            }
        }
        
        String[] result = new String[length];
        int pos = 0;
        
        for (String[] arr : arrs) {
            if (arr != null) {
                for (String element : arr) {
                    result[pos] = element;
                    pos++;
                }
            }
        }
        
        return result;
    }
    
    public static <T> boolean arrayContains(final T[] array, final T v) {
        if (v == null) {
            for (final T e : array) if (e == null) return true;
        } else {
            for (final T e : array) if (e == v || v.equals(e)) return true;
        }
        return false;
    }
    
    public static <T> List<T> mergeLists(List<T> list1, List<T> list2) {
	List<T> list = new ArrayList<>();

	list.addAll(list1);
	list.addAll(list2);

	return list;
    }
    
    public static String[] distinct(String[] source) {
        return (new HashSet<String>(Arrays.asList(source))).toArray(new String[0]);
    }
    
    public static Set<String> arrayToSet(String[] source) {
        return (new HashSet<>(Arrays.asList(source)));
    }
}
