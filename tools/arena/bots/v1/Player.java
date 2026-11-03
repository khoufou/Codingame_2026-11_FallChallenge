import java.util.*;
import java.io.*;
import java.math.*;

/**
 * TryAngle Catch bot.
 *
 * Key rule (from the referee): a captured triangle stays owned until the opponent captures it
 * or it is used, even if its corners are left empty. So units roam the map: each turn they are
 * assigned to the cheapest capturable triangles, threatened triangles are defended, and owned
 * triangles are spent on SPAWN when they are about to be lost or to grow the army early.
 **/
class Player {

    static final int ME = 0;
    static final int OPP = 1;
    static final int NONE = -1;
    static final int INF = 1_000_000;
    static final int MAX_TURNS = 200;
    // below this many turns left, a new unit no longer pays back the lost triangle income
    static final int EXPANSION_MIN_TURNS_LEFT = 40;

    static int houseCount;
    static int[] my, opp;
    static List<Integer>[] neighbors;
    static int[][] dist;
    static List<Triangle> triangles = new ArrayList<>();

    // per-turn plan
    static int[] free;       // units not assigned to any task yet
    static int[] committed;  // units planned to be on the house at the end of the turn
    static int[][] moves;    // moves[from][to] = amount

    static class Triangle {
        final int[] corners;
        final int owner;
        final boolean meCanCapture;

        Triangle(int house1, int house2, int house3, int owner, boolean meCanCapture) {
            this.corners = new int[]{house1, house2, house3};
            this.owner = owner;
            this.meCanCapture = meCanCapture;
        }

        String spawnCommand(int spawnHouse) {
            StringBuilder sb = new StringBuilder("SPAWN ").append(spawnHouse);
            for (int h : corners) if (h != spawnHouse) sb.append(' ').append(h);
            return sb.toString();
        }
    }

    static class Plan {
        Triangle triangle;
        List<int[]> pulls = new ArrayList<>(); // {from, to, amount}
        int maxDist;
        double score;
    }

    public static void main(String args[]) {
        Scanner in = new Scanner(System.in);
        houseCount = in.nextInt();
        for (int i = 0; i < houseCount; i++) {
            int houseId = in.nextInt();
            int houseX = in.nextInt();
            int houseY = in.nextInt();
        }
        my = new int[houseCount];
        opp = new int[houseCount];

        int turn = 0;
        // game loop
        while (true) {
            turn++;
            int myScore = in.nextInt();
            int opponentScore = in.nextInt();
            for (int i = 0; i < houseCount; i++) {
                int houseId = in.nextInt();
                my[houseId] = in.nextInt();
                opp[houseId] = in.nextInt();
            }
            neighbors = new List[houseCount];
            for (int i = 0; i < houseCount; i++) neighbors[i] = new ArrayList<>();
            int pathCount = in.nextInt();
            for (int i = 0; i < pathCount; i++) {
                int house1 = in.nextInt();
                int house2 = in.nextInt();
                if (!neighbors[house1].contains(house2)) {
                    neighbors[house1].add(house2);
                    neighbors[house2].add(house1);
                }
            }
            // the referee walks neighbors by increasing id when moving along a shortest path
            for (List<Integer> list : neighbors) Collections.sort(list);
            triangles.clear();
            int triangleCount = in.nextInt();
            for (int i = 0; i < triangleCount; i++) {
                int house1 = in.nextInt();
                int house2 = in.nextInt();
                int house3 = in.nextInt();
                int owner = in.nextInt();
                boolean meCanCapture = in.nextInt() != 0;
                boolean opponentCanCapture = in.nextInt() != 0;
                triangles.add(new Triangle(house1, house2, house3, owner, meCanCapture));
            }
            int linkableCount = in.nextInt();
            for (int i = 0; i < linkableCount; i++) {
                int house1 = in.nextInt();
                int house2 = in.nextInt();
            }

            computeDistances();
            System.out.println(play(MAX_TURNS - turn + 1));
        }
    }

    static String play(int turnsLeft) {
        free = my.clone();
        committed = new int[houseCount];
        moves = new int[houseCount][houseCount];
        List<String> spawns = new ArrayList<>();
        Set<Triangle> usedTriangles = new HashSet<>();

        // 1. defend owned triangles the opponent could complete next turn, spawn from them if hopeless
        for (Triangle t : triangles) {
            if (t.owner != ME || !threatened(t)) continue;
            int corner = -1;
            int cornerMissing = INF;
            for (int h : t.corners) {
                if (ownerOf(h) == OPP) continue;
                int missing = opp[h] + adjacentOpponents(h) + 1 - my[h];
                if (missing < cornerMissing) {
                    cornerMissing = missing;
                    corner = h;
                }
            }
            if (corner < 0) continue;

            committed[corner] += free[corner];
            free[corner] = 0;
            int reinforcements = 0;
            for (int n : neighbors[corner]) reinforcements += free[n];
            if (reinforcements >= cornerMissing) {
                for (int n : neighbors[corner]) {
                    int take = Math.min(cornerMissing, free[n]);
                    if (take <= 0) continue;
                    assign(n, corner, take);
                    cornerMissing -= take;
                }
            } else {
                spawns.add(t.spawnCommand(corner));
                usedTriangles.add(t);
            }
        }

        // 2. greedily assign free units to the best capturable triangles
        List<Triangle> targets = new ArrayList<>();
        for (Triangle t : triangles) {
            if (t.owner == ME || !t.meCanCapture) continue;
            boolean trap = false;
            for (int h : t.corners) trap |= surroundedBy(h, opp, my);
            if (!trap) targets.add(t);
        }
        List<Triangle> remaining = new ArrayList<>(targets);
        while (!remaining.isEmpty()) {
            Plan best = null;
            for (Triangle t : remaining) {
                Plan plan = evaluate(t, turnsLeft);
                if (plan != null && (best == null || plan.score > best.score)) best = plan;
            }
            if (best == null) break;
            for (int[] pull : best.pulls) assign(pull[0], pull[1], pull[2]);
            remaining.remove(best.triangle);
        }

        // 3. idle units gather towards the closest corner still to be taken
        for (int s = 0; s < houseCount; s++) {
            if (free[s] <= 0) continue;
            int target = -1;
            for (Triangle t : remaining) {
                for (int h : t.corners) {
                    // idle units already standing on h cover part of its need
                    if (h == s || needAt(h) <= free[h] || dist[s][h] >= INF) continue;
                    if (target < 0 || dist[s][h] < dist[s][target]) target = h;
                }
            }
            if (target >= 0) assign(s, target, free[s]);
        }

        // 4. never step into a house where the units would be surrounded
        int[] projected = project();
        for (int s = 0; s < houseCount; s++) {
            for (int d = 0; d < houseCount; d++) {
                if (moves[s][d] > 0 && surroundedBy(nextHop(s, d), opp, projected)) moves[s][d] = 0;
            }
        }

        // 5. early on, spend an exposed triangle to get one more unit when units are the bottleneck
        int ownedCount = 0;
        for (Triangle t : triangles) if (t.owner == ME && !usedTriangles.contains(t)) ownedCount++;
        if (spawns.isEmpty() && turnsLeft > EXPANSION_MIN_TURNS_LEFT && ownedCount >= 2
                && !remaining.isEmpty() && total(my) < houseCount / 2) {
            String spawn = expansionSpawn(remaining, usedTriangles);
            if (spawn != null) spawns.add(spawn);
        }

        List<String> commands = new ArrayList<>();
        for (int s = 0; s < houseCount; s++) {
            for (int d = 0; d < houseCount; d++) {
                if (moves[s][d] > 0) commands.add("MOVE " + s + " " + d + " " + moves[s][d]);
            }
        }
        commands.addAll(spawns);
        return commands.isEmpty() ? "WAIT" : String.join(";", commands);
    }

    static Plan evaluate(Triangle t, int turnsLeft) {
        Plan plan = new Plan();
        plan.triangle = t;
        int[] supply = free.clone();
        int used = 0;
        for (int h : t.corners) {
            int need = needAt(h);
            while (need > 0) {
                int src = -1;
                for (int s = 0; s < houseCount; s++) {
                    if (supply[s] > 0 && dist[s][h] < INF && (src < 0 || dist[s][h] < dist[src][h])) src = s;
                }
                if (src < 0) return null;
                int take = Math.min(need, supply[src]);
                supply[src] -= take;
                need -= take;
                used += take;
                plan.pulls.add(new int[]{src, h, take});
                plan.maxDist = Math.max(plan.maxDist, dist[src][h]);
            }
        }
        if (plan.maxDist >= turnsLeft) return null;
        double value = t.owner == OPP ? 2 : 1;
        plan.score = value / ((used + 1) * (1 + 0.3 * plan.maxDist));
        return plan;
    }

    static String expansionSpawn(List<Triangle> remaining, Set<Triangle> usedTriangles) {
        Set<Integer> frontier = new HashSet<>();
        for (Triangle t : remaining) for (int h : t.corners) if (needAt(h) > 0) frontier.add(h);

        Triangle bestTriangle = null;
        int bestHouse = -1;
        int bestExposure = INF;
        int bestFrontierDist = INF;
        for (Triangle t : triangles) {
            if (t.owner != ME || usedTriangles.contains(t)) continue;
            // prefer the triangle closest to the opponent: it is the most likely to be lost anyway
            int exposure = INF;
            int house = -1;
            int frontierDist = INF;
            for (int h : t.corners) {
                for (int o = 0; o < houseCount; o++) {
                    if (opp[o] > 0) exposure = Math.min(exposure, dist[h][o]);
                }
                for (int f : frontier) {
                    if (dist[h][f] < frontierDist) {
                        frontierDist = dist[h][f];
                        house = h;
                    }
                }
            }
            if (house < 0) continue;
            if (exposure < bestExposure || (exposure == bestExposure && frontierDist < bestFrontierDist)) {
                bestTriangle = t;
                bestHouse = house;
                bestExposure = exposure;
                bestFrontierDist = frontierDist;
            }
        }
        return bestTriangle == null ? null : bestTriangle.spawnCommand(bestHouse);
    }

    // an owned triangle is threatened when the opponent may own all three corners next turn
    static boolean threatened(Triangle t) {
        for (int h : t.corners) {
            if (ownerOf(h) != OPP && opp[h] + adjacentOpponents(h) <= my[h]) return false;
        }
        return true;
    }

    // extra units required on h to own it at the end of the turn
    static int needAt(int h) {
        if (surroundedBy(h, my, opp)) return 0;
        return Math.max(0, opp[h] + 1 - committed[h]);
    }

    static void assign(int from, int to, int amount) {
        free[from] -= amount;
        committed[to] += amount;
        if (from != to) moves[from][to] += amount;
    }

    // units of each house after this turn's moves (one step each, like the referee)
    static int[] project() {
        int[] projected = my.clone();
        for (int s = 0; s < houseCount; s++) {
            for (int d = 0; d < houseCount; d++) {
                if (moves[s][d] <= 0) continue;
                projected[s] -= moves[s][d];
                projected[nextHop(s, d)] += moves[s][d];
            }
        }
        return projected;
    }

    static int nextHop(int from, int to) {
        for (int n : neighbors[from]) {
            if (dist[n][to] < dist[from][to]) return n;
        }
        return from;
    }

    // house rule: every neighbor where the attacker has strictly more units than the defender
    static boolean surroundedBy(int h, int[] attacker, int[] defender) {
        if (neighbors[h].isEmpty()) return false;
        for (int n : neighbors[h]) {
            if (attacker[n] <= defender[n]) return false;
        }
        return true;
    }

    static int ownerOf(int h) {
        if (surroundedBy(h, my, opp)) return ME;
        if (surroundedBy(h, opp, my)) return OPP;
        if (my[h] > opp[h]) return ME;
        if (opp[h] > my[h]) return OPP;
        return NONE;
    }

    static int adjacentOpponents(int h) {
        int sum = 0;
        for (int n : neighbors[h]) sum += opp[n];
        return sum;
    }

    static int total(int[] units) {
        int sum = 0;
        for (int u : units) sum += u;
        return sum;
    }

    static void computeDistances() {
        dist = new int[houseCount][houseCount];
        for (int start = 0; start < houseCount; start++) {
            int[] d = dist[start];
            Arrays.fill(d, INF);
            d[start] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                int h = queue.poll();
                for (int n : neighbors[h]) {
                    if (d[n] == INF) {
                        d[n] = d[h] + 1;
                        queue.add(n);
                    }
                }
            }
        }
    }
}
