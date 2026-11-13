package dev.tslib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A hierarchical channel tree, implemented in pure Java.
 *
 * <pre>{@code
 * ChannelTree tree = ChannelTree.fromChannels(client.getChannels());
 * System.out.println(tree.printTree());
 * }</pre>
 */
public class ChannelTree implements AutoCloseable {
    private final Map<Long, Channel> channels = new HashMap<>();
    private final List<Long> roots = new ArrayList<>();
    private final Map<Long, List<Long>> children = new HashMap<>();

    public ChannelTree() {}

    /**
     * Build a tree from an array of channels.
     */
    public static ChannelTree fromChannels(Channel[] channels) {
        ChannelTree tree = new ChannelTree();
        if (channels != null) {
            for (Channel ch : channels) {
                if (ch != null && !tree.channels.containsKey(ch.id)) tree.addChannel(ch);
            }
        }
        return tree;
    }

    private void addChannel(Channel ch) {
        channels.put(ch.id, ch);
        if (ch.parentId == 0) {
            roots.add(ch.id);
        } else {
            children.computeIfAbsent(ch.parentId, k -> new ArrayList<>()).add(ch.id);
        }
    }

    /**
     * Sort a list of channel IDs according to the TS3 linked-list order field.
     * Each channel's {@code order} is the ID of the channel it comes after (0 = first).
     */
    private List<Long> sortByOrder(List<Long> ids) {
        if (ids == null || ids.size() <= 1) return ids;
        List<Long> input = new ArrayList<>(ids);
        Collections.sort(input);
        Map<Long, List<Long>> afterMap = new HashMap<>();
        Set<Long> siblingIds = new HashSet<>(input);
        for (Long id : input) {
            Channel ch = channels.get(id);
            if (ch != null) {
                // JNI exposes the predecessor as a 32-bit value; widen unsigned.
                long predecessor = Integer.toUnsignedLong(ch.order);
                afterMap.computeIfAbsent(predecessor, k -> new ArrayList<>()).add(id);
            }
        }
        List<Long> sorted = new ArrayList<>(ids.size());
        Set<Long> visited = new HashSet<>();
        for (Long id : afterMap.getOrDefault(0L, Collections.emptyList())) {
            appendChain(id, afterMap, visited, sorted);
        }
        for (Long id : input) {
            Channel ch = channels.get(id);
            if (ch != null && !siblingIds.contains(Integer.toUnsignedLong(ch.order))) {
                appendChain(id, afterMap, visited, sorted);
            }
        }
        for (Long id : input) appendChain(id, afterMap, visited, sorted);
        return sorted;
    }

    private void appendChain(Long first, Map<Long, List<Long>> afterMap,
                             Set<Long> visited, List<Long> result) {
        Long current = first;
        while (current != null && visited.add(current)) {
            result.add(current);
            current = null;
            for (Long next : afterMap.getOrDefault(result.get(result.size() - 1), Collections.emptyList())) {
                if (!visited.contains(next)) { current = next; break; }
            }
        }
    }

    /**
     * Get root channels (those with no parent), sorted by order.
     */
    public Channel[] getRoots() {
        return sortByOrder(roots).stream()
                .map(channels::get)
                .toArray(Channel[]::new);
    }

    /**
     * Get the children of a channel, sorted by order.
     */
    public Channel[] getChildren(long parentId) {
        List<Long> ids = children.get(parentId);
        if (ids == null) return new Channel[0];
        return sortByOrder(ids).stream()
                .map(channels::get)
                .toArray(Channel[]::new);
    }

    /**
     * Get the path from root to the specified channel.
     */
    public Channel[] pathTo(long id) {
        List<Channel> path = new ArrayList<>();
        long current = id;
        Set<Long> visited = new HashSet<>();
        while (current != 0 && channels.containsKey(current) && visited.add(current)) {
            Channel ch = channels.get(current);
            path.add(0, ch);
            current = ch.parentId;
        }
        return path.toArray(new Channel[0]);
    }

    /**
     * Find a channel by name (case-sensitive).
     *
     * @return the channel, or {@code null} if not found
     */
    public Channel findByName(String name) {
        for (Channel ch : channels.values()) {
            if (ch.name.equals(name)) return ch;
        }
        return null;
    }

    /**
     * Number of channels in the tree.
     */
    public int size() {
        return channels.size();
    }

    /**
     * Print an ASCII representation of the tree.
     */
    public String printTree() {
        StringBuilder sb = new StringBuilder();
        List<Long> sortedRoots = sortByOrder(roots);
        for (int i = 0; i < sortedRoots.size(); i++) {
            printNode(sb, sortedRoots.get(i), "", i == sortedRoots.size() - 1);
        }
        return sb.toString();
    }

    private void printNode(StringBuilder sb, long id, String prefix, boolean last) {
        Channel ch = channels.get(id);
        if (ch == null) return;

        sb.append(prefix);
        sb.append(last ? "└── " : "├── ");
        sb.append(ch.name).append("\n");

        List<Long> kids = sortByOrder(children.get(id));
        if (kids != null) {
            String childPrefix = prefix + (last ? "    " : "│   ");
            for (int i = 0; i < kids.size(); i++) {
                printNode(sb, kids.get(i), childPrefix, i == kids.size() - 1);
            }
        }
    }

    @Override
    public void close() {
        channels.clear();
        roots.clear();
        children.clear();
    }

    @Override
    public String toString() {
        return "ChannelTree(channels=" + channels.size() + ")";
    }
}
