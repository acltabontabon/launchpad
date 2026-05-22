package com.acltabontabon.launchpad.template;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal LCS-based line diff. Produces unified-style records (KEEP / ADD /
 * REMOVE) the Review screen renders with coloured prefixes. Not minimal in
 * the optimal-edit-script sense, but good enough for visually comparing
 * generated context to existing file content.
 */
public final class LineDiff {

    public enum Op { KEEP, ADD, REMOVE }

    public record Entry(Op op, String line) {}

    private LineDiff() {}

    public static List<Entry> diff(String before, String after) {
        var a = before == null ? new String[0] : before.split("\n", -1);
        var b = after == null ? new String[0] : after.split("\n", -1);
        int[][] lcs = lcsTable(a, b);
        var out = new ArrayList<Entry>();
        backtrack(a, b, lcs, a.length, b.length, out);
        java.util.Collections.reverse(out);
        return out;
    }

    private static int[][] lcsTable(String[] a, String[] b) {
        int[][] t = new int[a.length + 1][b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    t[i][j] = t[i - 1][j - 1] + 1;
                } else {
                    t[i][j] = Math.max(t[i - 1][j], t[i][j - 1]);
                }
            }
        }
        return t;
    }

    private static void backtrack(String[] a, String[] b, int[][] t, int i, int j, List<Entry> out) {
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i - 1].equals(b[j - 1])) {
                out.add(new Entry(Op.KEEP, a[i - 1]));
                i--; j--;
            } else if (j > 0 && (i == 0 || t[i][j - 1] >= t[i - 1][j])) {
                out.add(new Entry(Op.ADD, b[j - 1]));
                j--;
            } else {
                out.add(new Entry(Op.REMOVE, a[i - 1]));
                i--;
            }
        }
    }
}
