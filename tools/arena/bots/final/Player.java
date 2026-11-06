import java.util.*;

/**
 * TryAngle Catch bot: a heuristic plan, in several flavours, is played out for a few turns in a local copy of the
 * game, and the flavour with the best outcome is sent.
 *
 * Referee facts the strategy relies on:
 * - a captured triangle stays owned until the opponent captures it or it is used, even with empty corners;
 * - a used triangle can only be captured again by us once none of our units stands on its corners;
 * - units die when every neighbor house has a strict opponent majority.
 *
 * The base heuristic, each turn: ATTACK big opponent stacks, kill opponent stacks that can be surrounded, greedily
 * send free units to the most profitable captures (the closest first), vacate used triangles so they can be
 * recaptured, and spend triangles on SPAWN: the bigger army wins. No unit is held back for defense. Once our army
 * outnumbers the opponent's by half, the bot goes all out: captures are worth more the closer they are to the
 * opponent start, and idle units take the houses around opponent stacks to surround them.
 *
 * The flavours each change one decision of the base plan: attack threshold, spawn volume, which captures to go for,
 * surrounds and threatened triangles on or off... Each one is played in a port of the referee rules (ADD_PATH and
 * REMOVE_PATH are not simulated) against an opponent assumed to play the base heuristic, for LOOKAHEAD_TURNS turns,
 * both sides playing the base heuristic after the first one. The outcome counts the points scored meanwhile, the
 * triangles and units held at the end and the captures under way. The base plan is kept unless a flavour beats it,
 * so the time budget only limits how many flavours are tried.
 **/
class Player {

    static final int ME = 0;
    static final int OPP = 1;
    static final int NONE = -1;
    static final int INF = 1_000_000;
    static final int MAX_TURNS = 200;
    // a spawned unit needs some turns to pay back the income of the triangle it costs
    static final int SPAWN_MIN_TURNS_LEFT = 20;
    static final int MAX_SPAWNS_PER_TURN = 6;
    // spawn while the opponent army is not outnumbered by this margin
    static final int ARMY_MARGIN = 2;
    // surround checks enumerate subsets of neighbors
    static final int MAX_HALL_TARGETS = 10;
    // map zones: a triangle is central when its corners are about as far from both starts (in moves)
    static final double CENTER_WIDTH = 2;
    // the opening ends once we own this share of the central triangles, or after this turn
    static final double CENTER_SHARE = 0.5;
    static final int INVASION_MAX_TURN = 40;
    // all out once our army is this many times the opponent's
    static final double ALL_OUT_RATIO = 1.5;

    // simulation: each flavour of the plan is played out for this many turns (0 = send the base plan directly)
    static final int LOOKAHEAD_TURNS = intProperty("lookahead", 2);
    // flavours are tried while the turn is younger than this (ms); the first turn also warms the JIT up
    static final long BUDGET_NANOS = intProperty("budget", 30) * 1_000_000L;
    static final long WARMUP_NANOS = intProperty("warmup", 300) * 1_000_000L;
    // outcome of a simulation: points scored meanwhile, then for each triangle held the points of the next turns
    // (within the horizon), units, and captures under way, discounted for each turn they still need; a triangle we
    // used and still stand on counts as a capture BLOCKED_ETA turns away (step out, step back in)
    static final int INCOME_HORIZON = intProperty("horizon", 15);
    static final double UNIT_VALUE = doubleProperty("unitValue", 4);
    static final int BLOCKED_ETA = intProperty("blockedEta", 2);
    static final double POTENTIAL_DISCOUNT = doubleProperty("discount", 0.8);
    // the base plan is kept unless a flavour beats it by this margin
    static final double SWITCH_MARGIN = doubleProperty("margin", 0);

    /** Knobs of the heuristic: the base values, and one change each for the other flavours. */
    static class Params {
        final String name;
        // an attack costs a triangle and 3 units: only worth it against big stacks
        int attackMinKills = 4;
        // stealing a triangle also removes the opponent's income
        double oppTriangleValue = 2;
        // captures are ranked by value per turn of travel, the units they need count for little
        double unitCostWeight = 0.1;
        // corners guarded by bigger stacks are left alone
        int maxUnitsPerCorner = 8;
        // all out: captures are worth e^forward times more at the opponent start, e^forward times less at ours
        double forward = 2;
        // threatened triangles turn into units before the opponent takes them
        boolean defend = true;
        // kill the opponent stacks that can be surrounded
        boolean surround = true;
        // all out: idle units take the houses around opponent stacks
        boolean hunt = true;
        // 0 = no spawn, 1 = as many as the plan needs, 2 = from every triangle
        int spawnMode = 1;
        // the k most profitable captures are left for later
        int skipTargets = 0;

        Params(String name) {
            this.name = name;
        }
    }

    static final Params BASE = new Params("base");
    static final List<Params> FLAVOURS = flavours();
    // how the opponent is assumed to play: the base heuristic and/or a boss-like one (-Dmodels=base,boss); with
    // several models, each flavour is judged by its worst outcome (-Dcombine=min) or its average (avg)
    static final List<Params> OPPONENT_MODELS = opponentModels();
    static final boolean COMBINE_MIN = !"avg".equals(System.getProperty("combine", "min"));

    static List<Params> opponentModels() {
        List<Params> list = new ArrayList<>();
        for (String name : System.getProperty("models", "base").split(",")) {
            if (name.equals("boss")) {
                // the Silver boss attacks any stack of 2+ next to its full triangles, spawns from every triangle,
                // never surrounds and does not care about the front
                Params p = new Params("boss");
                p.attackMinKills = 2;
                p.spawnMode = 2;
                p.surround = false;
                p.hunt = false;
                p.forward = 0;
                p.defend = false;
                list.add(p);
            } else {
                list.add(BASE);
            }
        }
        return list;
    }

    static List<Params> flavours() {
        List<Params> list = new ArrayList<>();
        list.add(BASE);
        Params p;
        p = new Params("attack2"); p.attackMinKills = 2; list.add(p);
        p = new Params("noAttack"); p.attackMinKills = INF; list.add(p);
        p = new Params("spawnAll"); p.spawnMode = 2; list.add(p);
        p = new Params("noSpawn"); p.spawnMode = 0; list.add(p);
        p = new Params("skip1"); p.skipTargets = 1; list.add(p);
        p = new Params("skip2"); p.skipTargets = 2; list.add(p);
        p = new Params("noSurround"); p.surround = false; list.add(p);
        p = new Params("noDefend"); p.defend = false; list.add(p);
        p = new Params("steal"); p.oppTriangleValue = 4; list.add(p);
        p = new Params("neutral"); p.oppTriangleValue = 1; list.add(p);
        p = new Params("thrifty"); p.unitCostWeight = 1; list.add(p);
        p = new Params("bigCorners"); p.maxUnitsPerCorner = 12; list.add(p);
        p = new Params("noHunt"); p.hunt = false; p.forward = 0; list.add(p);
        // -Dflavours=name,name,... selects the flavours tried (the base plan always is). Spawn and surround variants
        // are left out by default: whether a surround works depends on where the opponent moves, and delaying a spawn
        // always looks good at a short horizon; both lost against the Silver boss
        String selection = System.getProperty("flavours", "attack2,noAttack,skip1,skip2,noDefend,steal,neutral,thrifty,bigCorners,noHunt");
        Set<String> names = new HashSet<>(Arrays.asList(selection.split(",")));
        list.removeIf(f -> f != BASE && !names.contains(f.name));
        return list;
    }

    /** Full game state, player 0 = us, player 1 = the opponent. */
    static class State {
        final int[][] units;          // units[player][house]
        final int[] owner;            // owner[triangle] = ME, OPP or NONE
        final boolean[][] canCapture; // canCapture[player][triangle]
        final int[] score = new int[2];
        int turn;                     // turn about to be played

        State(int houses, int triangles) {
            units = new int[2][houses];
            owner = new int[triangles];
            canCapture = new boolean[2][triangles];
        }

        State copy() {
            State s = new State(units[0].length, owner.length);
            for (int p = 0; p < 2; p++) {
                System.arraycopy(units[p], 0, s.units[p], 0, units[p].length);
                System.arraycopy(canCapture[p], 0, s.canCapture[p], 0, owner.length);
                s.score[p] = score[p];
            }
            System.arraycopy(owner, 0, s.owner, 0, owner.length);
            s.turn = turn;
            return s;
        }
    }

    /** One player's commands for a turn. */
    static class Actions {
        final List<int[]> attacks = new ArrayList<>(); // {triangle, target}
        final List<int[]> moves = new ArrayList<>();   // {from, to, amount}, to is the final destination
        final List<int[]> spawns = new ArrayList<>();  // {triangle, corner}

        String commands() {
            StringBuilder sb = new StringBuilder();
            for (int[] a : attacks) {
                sb.append("ATTACK");
                for (int c : cornersOf[a[0]]) sb.append(' ').append(c);
                sb.append(' ').append(a[1]).append(';');
            }
            for (int[] m : moves) sb.append("MOVE ").append(m[0]).append(' ').append(m[1]).append(' ').append(m[2]).append(';');
            for (int[] s : spawns) {
                sb.append("SPAWN ").append(s[1]);
                for (int c : cornersOf[s[0]]) if (c != s[1]) sb.append(' ').append(c);
                sb.append(';');
            }
            if (sb.length() == 0) return "WAIT";
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }
    }

    /** A triangle as seen by the player whose plan is being computed. */
    static class Triangle {
        final int index;
        final int[] corners;
        int owner;
        boolean meCanCapture;
        boolean opponentCanCapture;

        Triangle(int index, int house1, int house2, int house3) {
            this.index = index;
            this.corners = new int[]{house1, house2, house3};
        }

        // used by us: it can only be captured again once our units left all its corners
        boolean blocked() {
            return owner == NONE && !meCanCapture;
        }

        boolean capturable() {
            return owner != ME && meCanCapture;
        }

        boolean has(int h) {
            return corners[0] == h || corners[1] == h || corners[2] == h;
        }
    }

    static class Plan {
        Triangle triangle;
        List<int[]> pulls = new ArrayList<>(); // {from, to, amount}
        int eta;
        double score;
    }

    // map, rebuilt every turn (paths can be added and removed)
    static int houseCount;
    static int triangleCount;
    static int[][] neighbors;   // sorted by id, like the referee
    static boolean[][] adjacent;
    static int[][] dist;
    static int[][] byDistance;  // byDistance[h] = all houses sorted by distance from h
    static int[][] cornersOf;
    static List<Triangle> triangles = new ArrayList<>();

    // game, from our input
    static int realTurn;
    static long turnStart;
    static State real;
    static int[][] startOf = new int[2][];   // houses holding units of each player on the first turn
    static int[][] frontOf = new int[2][];   // moves from the player's start minus moves from the other start
    static int startGap = 1;                 // moves between the two starts
    static boolean[] openingOver = new boolean[2];

    // view of the player whose plan is being computed
    static int viewer;
    static Params params;
    static int[] my, opp;
    static int turn, turnsLeft;
    static int[] startMine, startTheirs, front;
    static boolean invasionDone;
    static boolean allOut;                   // the viewer's army is big enough to go all out

    // plan under construction
    static Actions actions;
    static int[] free;              // units not assigned to any task yet
    static int[] stay;              // units assigned to stay on their house
    static int[] arrivals;          // units entering the house this turn
    static int[] destined;          // units whose final destination is the house
    static int[] spawned;           // units spawned on the house this turn
    static int[][] moves;           // moves[from][to] = amount
    static boolean[] noEntry;       // corners of used triangles being vacated this turn
    static boolean[] usedTri;       // triangles spent this turn
    static List<Triangle> remainingTargets = new ArrayList<>();
    static List<Triangle> skippedTargets = new ArrayList<>();
    static List<Plan> chosenPlans = new ArrayList<>();

    // scratch buffers
    static int[] planSupply;
    static int[] sourceHouse, sourceMask, sourceStamp;
    static int stamp;
    static boolean[][] advantage;
    static int[] nodeOwner;

    public static void main(String args[]) {
        Scanner in = new Scanner(System.in);
        readInit(in);
        while (true) {
            realTurn++;
            readTurn(in);
            System.out.println(decide());
        }
    }

    static void readInit(Scanner in) {
        houseCount = in.nextInt();
        for (int i = 0; i < houseCount; i++) {
            int houseId = in.nextInt();
            int houseX = in.nextInt();
            int houseY = in.nextInt();
        }
        my = new int[houseCount];
        opp = new int[houseCount];
        free = new int[houseCount];
        stay = new int[houseCount];
        arrivals = new int[houseCount];
        destined = new int[houseCount];
        spawned = new int[houseCount];
        moves = new int[houseCount][houseCount];
        noEntry = new boolean[houseCount];
        adjacent = new boolean[houseCount][houseCount];
        planSupply = new int[houseCount];
        sourceHouse = new int[houseCount];
        sourceMask = new int[houseCount];
        sourceStamp = new int[houseCount];
        advantage = new boolean[2][houseCount];
        nodeOwner = new int[houseCount];
        realTurn = 0;
        startGap = 1;
        openingOver = new boolean[2];
    }

    static void readTurn(Scanner in) {
        int myScore = in.nextInt();
        turnStart = System.nanoTime();
        int opponentScore = in.nextInt();
        int[] myUnits = new int[houseCount], oppUnits = new int[houseCount];
        for (int i = 0; i < houseCount; i++) {
            int houseId = in.nextInt();
            myUnits[houseId] = in.nextInt();
            oppUnits[houseId] = in.nextInt();
        }
        List<List<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < houseCount; i++) adjacency.add(new ArrayList<>());
        int pathCount = in.nextInt();
        for (int i = 0; i < pathCount; i++) {
            int house1 = in.nextInt();
            int house2 = in.nextInt();
            if (!adjacency.get(house1).contains(house2)) {
                adjacency.get(house1).add(house2);
                adjacency.get(house2).add(house1);
            }
        }
        neighbors = new int[houseCount][];
        for (boolean[] row : adjacent) Arrays.fill(row, false);
        for (int i = 0; i < houseCount; i++) {
            // the referee walks neighbors by increasing id when moving along a shortest path
            List<Integer> list = adjacency.get(i);
            Collections.sort(list);
            neighbors[i] = new int[list.size()];
            for (int k = 0; k < list.size(); k++) {
                neighbors[i][k] = list.get(k);
                adjacent[i][list.get(k)] = true;
            }
        }
        triangleCount = in.nextInt();
        triangles.clear();
        cornersOf = new int[triangleCount][];
        real = new State(houseCount, triangleCount);
        for (int i = 0; i < triangleCount; i++) {
            int house1 = in.nextInt();
            int house2 = in.nextInt();
            int house3 = in.nextInt();
            Triangle t = new Triangle(i, house1, house2, house3);
            triangles.add(t);
            cornersOf[i] = t.corners;
            real.owner[i] = in.nextInt();
            real.canCapture[ME][i] = in.nextInt() != 0;
            real.canCapture[OPP][i] = in.nextInt() != 0;
        }
        int linkableCount = in.nextInt();
        for (int i = 0; i < linkableCount; i++) {
            int house1 = in.nextInt();
            int house2 = in.nextInt();
        }
        real.units[ME] = myUnits;
        real.units[OPP] = oppUnits;
        real.score[ME] = myScore;
        real.score[OPP] = opponentScore;
        real.turn = realTurn;

        computeDistances();
        if (realTurn == 1) {
            startOf[ME] = housesWithUnits(myUnits);
            startOf[OPP] = housesWithUnits(oppUnits);
        }
        computeFronts();
        usedTri = new boolean[triangleCount];
        for (int p = 0; p < 2; p++) {
            load(real, p, BASE);
            computeOpening();
            openingOver[p] = invasionDone;
        }
    }

    // ------------------------------------------------------------------ decision

    /** The base plan, or the flavour whose simulated outcome is the best. */
    static String decide() {
        // no simulation when the plan is not wanted, or when reading the input already ate the budget (slow machine)
        if (LOOKAHEAD_TURNS <= 0 || (realTurn > 1 && System.nanoTime() - turnStart > BUDGET_NANOS / 2)) {
            return plan(real, ME, BASE).commands();
        }
        boolean warmup = realTurn == 1;
        long budget = warmup ? WARMUP_NANOS + 100_000_000L : BUDGET_NANOS;
        Actions best = null;
        Params bestParams = BASE;
        double bestValue = 0, baseValue = 0;
        int tried = 0;
        do {
            Actions[] theirs = new Actions[OPPONENT_MODELS.size()];
            for (int m = 0; m < theirs.length; m++) theirs[m] = plan(real, OPP, OPPONENT_MODELS.get(m));
            best = null;
            tried = 0;
            for (Params flavour : FLAVOURS) {
                if (best != null && System.nanoTime() - turnStart > budget) break;
                Actions mine = plan(real, ME, flavour);
                double value = COMBINE_MIN ? Double.MAX_VALUE : 0;
                for (int m = 0; m < theirs.length; m++) {
                    double outcome = rollout(mine, theirs[m], OPPONENT_MODELS.get(m));
                    value = COMBINE_MIN ? Math.min(value, outcome) : value + outcome / theirs.length;
                }
                tried++;
                if (best == null) {
                    best = mine;
                    bestParams = flavour;
                    baseValue = bestValue = value;
                } else if (value > bestValue && value > baseValue + SWITCH_MARGIN) {
                    best = mine;
                    bestParams = flavour;
                    bestValue = value;
                }
            }
        } while (warmup && System.nanoTime() - turnStart < WARMUP_NANOS);
        System.err.printf("turn %d: %s %.1f (base %.1f), %d flavours, %d ms%n", realTurn, bestParams.name, bestValue, baseValue,
                tried, (System.nanoTime() - turnStart) / 1_000_000);
        return best.commands();
    }

    /** Outcome of playing 'mine' against 'theirs' this turn, then the base heuristic against the opponent model. */
    static double rollout(Actions mine, Actions theirs, Params opponentModel) {
        State s = real.copy();
        simulate(s, mine, theirs);
        for (int k = 1; k < LOOKAHEAD_TURNS && s.turn <= MAX_TURNS; k++) {
            Actions a = plan(s, ME, BASE);
            Actions b = plan(s, OPP, opponentModel);
            simulate(s, a, b);
        }
        return evaluate(s);
    }

    static double evaluate(State s) {
        double value = (s.score[ME] - s.score[OPP]) - (real.score[ME] - real.score[OPP]);
        int held = 0;
        for (int t = 0; t < triangleCount; t++) {
            if (s.owner[t] == ME) held++;
            else if (s.owner[t] == OPP) held--;
        }
        int horizon = Math.max(0, Math.min(MAX_TURNS - s.turn + 1, INCOME_HORIZON));
        value += held * horizon;
        value += (total(s.units[ME]) - total(s.units[OPP])) * UNIT_VALUE;
        value += potential(s, ME) - potential(s, OPP);
        return value;
    }

    /** Points the player can expect from the captures the base heuristic would start now and the triangles it used. */
    static double potential(State s, int p) {
        load(s, p, BASE);
        resetPlan();
        System.arraycopy(my, 0, free, 0, houseCount);
        allOut = false;
        captureTriangles();
        int horizon = Math.max(0, Math.min(turnsLeft - 1, INCOME_HORIZON));
        double sum = 0;
        for (Plan plan : chosenPlans) {
            double value = (plan.triangle.owner == OPP ? 2 : 1) * Math.max(0, horizon - plan.eta);
            sum += value * Math.pow(POTENTIAL_DISCOUNT, plan.eta + 1);
        }
        for (Triangle t : triangles) {
            if (t.blocked()) sum += Math.max(0, horizon - BLOCKED_ETA) * Math.pow(POTENTIAL_DISCOUNT, BLOCKED_ETA + 1);
        }
        return sum;
    }

    // ------------------------------------------------------------------ simulation (port of the referee rules)

    /** Plays one turn: attacks, then moves, then spawns, then surround kills, captures and scoring. */
    static void simulate(State s, Actions a0, Actions a1) {
        Actions[] acts = {a0, a1};
        int[][] units = s.units;
        int[][] remaining = {units[0].clone(), units[1].clone()};
        // attacks: each frame applies the first valid attack of each player, both checked before either is applied
        int[] next = new int[2];
        int[] tri = new int[2], target = new int[2];
        while (true) {
            int found = 0;
            for (int p = 0; p < 2; p++) {
                tri[p] = -1;
                List<int[]> attacks = acts[p].attacks;
                while (next[p] < attacks.size()) {
                    int[] a = attacks.get(next[p]++);
                    if (canUse(s, remaining, p, a[0], 1) && touches(a[0], a[1])) {
                        tri[p] = a[0];
                        target[p] = a[1];
                        found++;
                        break;
                    }
                }
            }
            if (found == 0) break;
            for (int p = 0; p < 2; p++) {
                if (tri[p] < 0) continue;
                use(s, remaining, p, tri[p], 1);
                units[1 - p][target[p]] = 0;
                remaining[1 - p][target[p]] = 0;
            }
        }
        // moves: one house along the shortest path, units that arrived or spawned cannot move again
        for (int p = 0; p < 2; p++) {
            for (int[] m : acts[p].moves) {
                int from = m[0], to = m[1];
                if (m[2] <= 0 || from == to || remaining[p][from] <= 0 || dist[from][to] >= INF) continue;
                int amount = Math.min(m[2], remaining[p][from]);
                int hop = nextHop(from, to);
                units[p][from] -= amount;
                remaining[p][from] -= amount;
                units[p][hop] += amount;
            }
        }
        // spawns
        for (int p = 0; p < 2; p++) {
            for (int[] sp : acts[p].spawns) {
                if (!canUse(s, remaining, p, sp[0], 0)) continue;
                use(s, remaining, p, sp[0], 0);
                units[p][sp[1]]++;
            }
        }
        finalizeTurn(s);
        s.turn++;
    }

    static boolean canUse(State s, int[][] remaining, int p, int t, int unitsNeeded) {
        if (s.owner[t] != p) return false;
        for (int c : cornersOf[t]) if (remaining[p][c] < unitsNeeded) return false;
        return true;
    }

    static void use(State s, int[][] remaining, int p, int t, int unitsTaken) {
        for (int c : cornersOf[t]) {
            int take = Math.min(unitsTaken, s.units[p][c]);
            s.units[p][c] -= take;
            remaining[p][c] -= take;
        }
        s.canCapture[p][t] = false;
        s.owner[t] = NONE;
    }

    static boolean touches(int t, int house) {
        for (int c : cornersOf[t]) if (adjacent[c][house]) return true;
        return false;
    }

    static void finalizeTurn(State s) {
        boolean killsChecked = false;
        while (true) {
            updateAllowedCaptures(s);
            if (!killsChecked) {
                killsChecked = true;
                if (surroundNodes(s)) continue;
            }
            if (!captureTriangles(s)) break;
        }
        for (int t = 0; t < triangleCount; t++) {
            if (s.owner[t] != NONE) s.score[s.owner[t]]++;
        }
    }

    static void updateAllowedCaptures(State s) {
        for (int t = 0; t < triangleCount; t++) {
            for (int p = 0; p < 2; p++) {
                if (s.owner[t] != NONE) {
                    s.canCapture[p][t] = s.owner[t] != p;
                } else if (!s.canCapture[p][t]) {
                    int[] c = cornersOf[t];
                    s.canCapture[p][t] = s.units[p][c[0]] == 0 && s.units[p][c[1]] == 0 && s.units[p][c[2]] == 0;
                }
            }
        }
    }

    static boolean surroundNodes(State s) {
        int[][] units = s.units;
        for (int h = 0; h < houseCount; h++) {
            advantage[0][h] = units[0][h] > units[1][h];
            advantage[1][h] = units[1][h] > units[0][h];
        }
        boolean killed = false;
        for (int p = 0; p < 2; p++) {
            boolean[] theirs = advantage[1 - p];
            for (int h = 0; h < houseCount; h++) {
                if (units[p][h] == 0 || neighbors[h].length == 0) continue;
                boolean surrounded = true;
                for (int n : neighbors[h]) surrounded &= theirs[n];
                if (surrounded) {
                    units[p][h] = 0;
                    killed = true;
                }
            }
        }
        return killed;
    }

    static boolean captureTriangles(State s) {
        for (int h = 0; h < houseCount; h++) nodeOwner[h] = ownerOf(h, s.units[0], s.units[1]);
        boolean changed = false;
        for (int t = 0; t < triangleCount; t++) {
            int[] c = cornersOf[t];
            int old = s.owner[t];
            for (int p = 0; p < 2; p++) {
                if (s.canCapture[p][t] && nodeOwner[c[0]] == p && nodeOwner[c[1]] == p && nodeOwner[c[2]] == p) s.owner[t] = p;
            }
            changed |= s.owner[t] != old;
        }
        return changed;
    }

    // ------------------------------------------------------------------ heuristic plan

    /** The plan of player p on state s with the given knobs. */
    static Actions plan(State s, int p, Params knobs) {
        load(s, p, knobs);
        resetPlan();
        actions = new Actions();
        computeOpening();
        allOut = total(my) >= ALL_OUT_RATIO * total(opp);

        attackStacks();
        System.arraycopy(my, 0, free, 0, houseCount);
        spawnThreatened();
        surroundOpponents();
        captureTriangles();
        vacateUsedTriangles();
        moveIdleUnits();
        growArmy();

        for (int from = 0; from < houseCount; from++) {
            for (int to = 0; to < houseCount; to++) {
                if (moves[from][to] > 0) actions.moves.add(new int[]{from, to, moves[from][to]});
            }
        }
        return actions;
    }

    static void load(State s, int p, Params knobs) {
        viewer = p;
        params = knobs;
        System.arraycopy(s.units[p], 0, my, 0, houseCount);
        System.arraycopy(s.units[1 - p], 0, opp, 0, houseCount);
        for (Triangle t : triangles) {
            int owner = s.owner[t.index];
            t.owner = owner == NONE ? NONE : owner == p ? ME : OPP;
            t.meCanCapture = s.canCapture[p][t.index];
            t.opponentCanCapture = s.canCapture[1 - p][t.index];
        }
        turn = s.turn;
        turnsLeft = MAX_TURNS - turn + 1;
        startMine = startOf[p];
        startTheirs = startOf[1 - p];
        front = frontOf[p];
    }

    static void resetPlan() {
        Arrays.fill(stay, 0);
        Arrays.fill(arrivals, 0);
        Arrays.fill(destined, 0);
        Arrays.fill(spawned, 0);
        Arrays.fill(noEntry, false);
        Arrays.fill(usedTri, false);
        for (int[] row : moves) Arrays.fill(row, 0);
        remainingTargets.clear();
        chosenPlans.clear();
    }

    /**
     * 0. attacks are applied before moves: a triangle wipes out the opponent stack on an adjacent house,
     * at the cost of the triangle and one unit on each of its corners. My and opp are updated accordingly.
     */
    static void attackStacks() {
        if (turnsLeft <= 1) return;
        while (true) {
            int target = -1;
            Triangle source = null;
            for (int h = 0; h < houseCount; h++) {
                if (opp[h] < params.attackMinKills || (target >= 0 && opp[h] <= opp[target])) continue;
                Triangle t = attackSource(h);
                if (t != null) {
                    target = h;
                    source = t;
                }
            }
            if (target < 0) return;
            for (int c : source.corners) my[c]--;
            actions.attacks.add(new int[]{source.index, target});
            usedTri[source.index] = true;
            opp[target] = 0;
        }
    }

    // owned triangle next to the target with a unit on every corner, keeping the most units on its corners
    static Triangle attackSource(int target) {
        Triangle best = null;
        int bestUnits = -1;
        for (Triangle t : triangles) {
            if (t.owner != ME || usedTri[t.index]) continue;
            boolean next = false;
            int units = INF;
            for (int c : t.corners) {
                units = Math.min(units, my[c]);
                next |= adjacent[c][target];
            }
            if (next && units >= 1 && units > bestUnits) {
                best = t;
                bestUnits = units;
            }
        }
        return best;
    }

    /**
     * 1. owned triangles are not defended: those the opponent could complete next turn, and already contests,
     * turn into a unit before the opponent takes them.
     */
    static void spawnThreatened() {
        if (!params.defend || turnsLeft <= 1) return;
        int[] planned = planned();
        List<Triangle> threatened = new ArrayList<>();
        for (Triangle t : triangles) {
            if (t.owner == ME && !usedTri[t.index] && oppCanTakeAll(t.corners, planned)) threatened.add(t);
        }
        for (Triangle t : threatened) {
            boolean engaged = false;
            for (int c : t.corners) engaged |= ownerOf(c, my, opp) == OPP;
            if (!engaged) continue;
            int spawnCorner = -1;
            for (int c : t.corners) {
                planned[c]++;
                boolean safe = !oppCanTakeAll(t.corners, planned);
                planned[c]--;
                if (safe) {
                    spawnCorner = c;
                    break;
                }
            }
            if (spawnCorner < 0) {
                for (int c : t.corners) {
                    if (spawnCorner < 0 || planned[c] > planned[spawnCorner]) spawnCorner = c;
                }
            }
            spawn(t, spawnCorner);
            planned[spawnCorner]++;
        }
    }

    // 2. kill opponent stacks: a strict majority on every neighbor of their house kills them whatever their size
    static void surroundOpponents() {
        if (!params.surround) return;
        List<Integer> stacks = new ArrayList<>();
        for (int h = 0; h < houseCount; h++) if (opp[h] > 0) stacks.add(h);
        stacks.sort((a, b) -> opp[b] - opp[a]);
        for (int h : stacks) {
            int[] planned = planned();
            int[] demand = new int[neighbors[h].length];
            int total = 0;
            for (int i = 0; i < demand.length; i++) {
                int n = neighbors[h][i];
                demand[i] = Math.max(0, opp[n] + 1 - planned[n]);
                total += demand[i];
            }
            if (total == 0) continue;
            List<int[]> pulls = supplyDemands(neighbors[h], demand, total);
            if (pulls == null) continue;
            for (int[] pull : pulls) assign(pull[0], pull[1], pull[2]);
        }
    }

    /**
     * Free units covering the demand of every sink this turn, each unit moving at most one house
     * (max flow from free stacks to the sinks), or null when the demand cannot be met.
     */
    static List<int[]> supplyDemands(int[] sinks, int[] demand, int total) {
        int m = sinks.length;
        stamp++;
        int[] sources = new int[houseCount];
        int sourceCount = 0;
        for (int sink : sinks) {
            for (int k = -1; k < neighbors[sink].length; k++) {
                int s = k < 0 ? sink : neighbors[sink][k];
                if (free[s] > 0 && sourceStamp[s] != stamp) {
                    sourceStamp[s] = stamp;
                    sources[sourceCount++] = s;
                }
            }
        }
        // nodes: 0 = start, sources, sinks, end
        int size = sourceCount + m + 2, start = 0, end = size - 1;
        int[][] residual = new int[size][size];
        for (int i = 0; i < sourceCount; i++) {
            residual[start][1 + i] = free[sources[i]];
            for (int j = 0; j < m; j++) {
                if (dist[sources[i]][sinks[j]] <= 1) residual[1 + i][1 + sourceCount + j] = INF;
            }
        }
        for (int j = 0; j < m; j++) residual[1 + sourceCount + j][end] = demand[j];
        int flow = 0;
        int[] parent = new int[size];
        int[] queue = new int[size];
        while (flow < total) {
            Arrays.fill(parent, -1);
            parent[start] = start;
            int head = 0, tail = 0;
            queue[tail++] = start;
            while (head < tail && parent[end] < 0) {
                int u = queue[head++];
                for (int v = 0; v < size; v++) {
                    if (parent[v] < 0 && residual[u][v] > 0) {
                        parent[v] = u;
                        queue[tail++] = v;
                    }
                }
            }
            if (parent[end] < 0) return null;
            int push = INF;
            for (int v = end; v != start; v = parent[v]) push = Math.min(push, residual[parent[v]][v]);
            for (int v = end; v != start; v = parent[v]) {
                residual[parent[v]][v] -= push;
                residual[v][parent[v]] += push;
            }
            flow += push;
        }
        List<int[]> pulls = new ArrayList<>();
        for (int i = 0; i < sourceCount; i++) {
            for (int j = 0; j < m; j++) {
                int used = residual[1 + sourceCount + j][1 + i];
                if (dist[sources[i]][sinks[j]] <= 1 && used > 0) pulls.add(new int[]{sources[i], sinks[j], used});
            }
        }
        return pulls;
    }

    // 3. greedily assign free units to the most profitable capturable triangles
    static void captureTriangles() {
        remainingTargets.clear();
        skippedTargets.clear();
        chosenPlans.clear();
        for (Triangle t : triangles) {
            if (!t.capturable()) continue;
            boolean trap = false;
            for (int c : t.corners) trap |= surroundedBy(c, opp, my);
            if (!trap) remainingTargets.add(t);
        }
        int[] supply = free.clone();
        while (!remainingTargets.isEmpty()) {
            Plan best = null;
            for (Triangle t : remainingTargets) {
                Plan plan = capturePlan(t, supply);
                if (plan != null && (best == null || plan.score > best.score)) best = plan;
            }
            if (best == null) break;
            remainingTargets.remove(best.triangle);
            if (skippedTargets.size() < params.skipTargets) {
                skippedTargets.add(best.triangle);
                continue;
            }
            for (int[] pull : best.pulls) {
                assign(pull[0], pull[1], pull[2]);
                supply[pull[0]] -= pull[2];
            }
            chosenPlans.add(best);
        }
        // skipped captures still attract idle units and spawns
        remainingTargets.addAll(skippedTargets);
    }

    static Plan capturePlan(Triangle t, int[] units) {
        Plan plan = new Plan();
        plan.triangle = t;
        int[] supply = planSupply;
        System.arraycopy(units, 0, supply, 0, houseCount);
        int used = 0;
        for (int c : t.corners) {
            if (opp[c] + 1 > params.maxUnitsPerCorner) return null;
            int need = opp[c] + 1 - destined[c];
            for (int s : byDistance[c]) {
                if (need <= 0 || dist[s][c] >= INF) break;
                if (supply[s] <= 0) continue;
                int take = Math.min(need, supply[s]);
                supply[s] -= take;
                need -= take;
                used += take;
                plan.pulls.add(new int[]{s, c, take});
                plan.eta = Math.max(plan.eta, dist[s][c]);
            }
            if (need > 0) return null;
        }
        // the capture must be scored at least once before the game ends
        if (plan.eta >= turnsLeft - 1) return null;
        double value = (t.owner == OPP ? params.oppTriangleValue : 1) * (turnsLeft - 1 - plan.eta);
        if (allOut && params.forward > 0) value *= Math.exp(params.forward * zone(t) / startGap);
        plan.score = value / (plan.eta + 1) / (1.0 + params.unitCostWeight * used);
        return plan;
    }

    // 4. units standing on a used triangle step out so that it becomes capturable again
    static void vacateUsedTriangles() {
        int[] goal = goalDistances();
        for (Triangle t : triangles) {
            if (!t.blocked()) continue;
            boolean possible = true;
            for (int c : t.corners) {
                if (stay[c] > 0 || arrivals[c] > 0) possible = false;
                if (free[c] > 0 && exit(c, t, goal) < 0) possible = false;
            }
            if (!possible) continue;
            for (int c : t.corners) noEntry[c] = true;
            for (int c : t.corners) {
                if (free[c] > 0) assign(c, exit(c, t, goal), free[c]);
            }
        }
    }

    static int exit(int c, Triangle t, int[] goal) {
        int best = -1;
        for (int x : neighbors[c]) {
            if (t.has(x) || noEntry[x]) continue;
            if (best < 0 || goal[x] < goal[best]) best = x;
        }
        return best;
    }

    /**
     * 5. when all out, idle units first take the houses around opponent stacks. The others gather towards the closest
     * corner still lacking units away from our start, chase opponent units, or go back to the triangles near our start.
     */
    static void moveIdleUnits() {
        for (int s = 0; s < houseCount; s++) {
            if (allOut && params.hunt) hunt(s);
            if (free[s] <= 0) continue;
            int target = closestCorner(s, false);
            if (target < 0) {
                for (int h = 0; h < houseCount; h++) {
                    if (opp[h] == 0 || h == s || dist[s][h] >= INF || noEntry[nextHop(s, h)]) continue;
                    if (target < 0 || dist[s][h] < dist[s][target]) target = h;
                }
            }
            if (target < 0) target = closestCorner(s, true);
            if (target >= 0) assign(s, target, free[s]);
        }
    }

    // units on s take the houses next to opponent stacks where we still lack the majority, to surround them later
    static void hunt(int s) {
        while (free[s] > 0) {
            int target = huntTarget(s);
            if (target < 0) return;
            assign(s, target, Math.min(free[s], opp[target] + 1 - destined[target]));
        }
    }

    // house next to an opponent stack where we still lack the majority: big stacks and short trips first
    static int huntTarget(int s) {
        int target = -1;
        double best = -1;
        for (int h = 0; h < houseCount; h++) {
            if (opp[h] == 0 || dist[s][h] >= INF) continue;
            for (int n : neighbors[h]) {
                if (opp[n] + 1 - destined[n] <= 0) continue;
                if (n != s && noEntry[nextHop(s, n)]) continue;
                double score = opp[h] / (1.0 + dist[s][n]);
                if (score > best) {
                    best = score;
                    target = n;
                }
            }
        }
        return target;
    }

    // closest corner lacking units among the remaining targets, those near our start only when includeRear
    static int closestCorner(int s, boolean includeRear) {
        int target = -1;
        for (Triangle t : remainingTargets) {
            if (!includeRear && isRear(t)) continue;
            for (int c : t.corners) {
                if (c == s || dist[s][c] >= INF || opp[c] + 1 - destined[c] <= free[c]) continue;
                if (noEntry[nextHop(s, c)]) continue;
                if (target < 0 || dist[s][c] < dist[s][target]) target = c;
            }
        }
        return target;
    }

    // 6. spend owned triangles on new units while units are the bottleneck
    static void growArmy() {
        if (params.spawnMode == 0 || turnsLeft <= SPAWN_MIN_TURNS_LEFT) return;
        int outstanding = 0;
        for (Triangle t : remainingTargets) {
            for (int c : t.corners) outstanding += Math.max(0, opp[c] + 1 - destined[c]);
        }
        int myTotal = total(my) + actions.spawns.size();
        int oppTotal = total(opp);
        int wanted = outstanding;
        if (myTotal < oppTotal + ARMY_MARGIN) wanted = Math.max(wanted, oppTotal + ARMY_MARGIN - myTotal);
        wanted = Math.min(wanted, MAX_SPAWNS_PER_TURN) - actions.spawns.size();
        if (params.spawnMode == 2) wanted = triangleCount;

        int[] goal = goalDistances();
        int[] end = endOfTurn();
        while (true) {
            double[] bonus = spawnBonus(end);
            Triangle bestTriangle = null;
            int bestCorner = -1;
            double bestCost = INF;
            for (Triangle t : triangles) {
                if (t.owner != ME || usedTri[t.index]) continue;
                // committed units on the corners would delay the recapture
                double cost = 0;
                for (int c : t.corners) cost += 10 * (stay[c] + arrivals[c]);
                if (oppCanTakeAll(t.corners, end)) cost += 100;
                for (int c : t.corners) {
                    // beyond the wanted amount, only spawns completing a capture or a kill this turn are worth it
                    if (wanted <= 0 && bonus[c] <= 0) continue;
                    double cornerCost = cost + Math.min(goal[c], 50) - bonus[c];
                    if (cornerCost < bestCost) {
                        bestCost = cornerCost;
                        bestTriangle = t;
                        bestCorner = c;
                    }
                }
            }
            if (bestTriangle == null) return;
            spawn(bestTriangle, bestCorner);
            end[bestCorner]++;
            wanted--;
        }
    }

    // value of one more unit on each house at the end of the turn: it completes a capture or a kill
    static double[] spawnBonus(int[] end) {
        double[] bonus = new double[houseCount];
        for (Triangle t : remainingTargets) {
            int missing = -1;
            for (int c : t.corners) {
                if (end[c] > opp[c]) continue;
                if (missing >= 0 || end[c] + 1 <= opp[c]) {
                    missing = -2;
                    break;
                }
                missing = c;
            }
            if (missing >= 0) bonus[missing] += t.owner == OPP ? 100 : 50;
        }
        for (int h = 0; h < houseCount; h++) {
            if (opp[h] == 0) continue;
            int missing = -1;
            for (int nb : neighbors[h]) {
                if (end[nb] > opp[nb]) continue;
                if (missing >= 0 || end[nb] + 1 <= opp[nb]) {
                    missing = -2;
                    break;
                }
                missing = nb;
            }
            if (missing >= 0) bonus[missing] += 20 * opp[h];
        }
        return bonus;
    }

    static void spawn(Triangle t, int corner) {
        actions.spawns.add(new int[]{t.index, corner});
        usedTri[t.index] = true;
        spawned[corner]++;
    }

    // distance from each house to the closest corner of a remaining target that still lacks units
    static int[] goalDistances() {
        int[] goal = new int[houseCount];
        Arrays.fill(goal, INF);
        for (Triangle t : remainingTargets) {
            // the opening spawns units towards the front, not back home
            if (!invasionDone && isRear(t)) continue;
            for (int c : t.corners) {
                if (opp[c] + 1 - destined[c] <= 0) continue;
                for (int h = 0; h < houseCount; h++) goal[h] = Math.min(goal[h], dist[h][c]);
            }
        }
        return goal;
    }

    static void assign(int from, int to, int amount) {
        if (amount <= 0) return;
        free[from] -= amount;
        destined[to] += amount;
        if (from == to) {
            stay[from] += amount;
        } else {
            moves[from][to] += amount;
            arrivals[nextHop(from, to)] += amount;
        }
    }

    // units that will certainly be on each house at the end of the turn
    static int[] planned() {
        int[] planned = new int[houseCount];
        for (int h = 0; h < houseCount; h++) planned[h] = stay[h] + arrivals[h] + spawned[h];
        return planned;
    }

    static int[] endOfTurn() {
        int[] end = planned();
        for (int h = 0; h < houseCount; h++) end[h] += free[h];
        return end;
    }

    /**
     * True when the opponent, moving each unit at most one house, can end the turn with strictly more units
     * than 'ours' on every target house (generalized Hall condition of the supply/demand problem).
     */
    static boolean oppCanTakeAll(int[] targets, int[] ours) {
        int m = targets.length;
        if (m == 0 || m > MAX_HALL_TARGETS) return false;
        stamp++;
        int sources = 0;
        for (int i = 0; i < m; i++) {
            int t = targets[i];
            for (int k = -1; k < neighbors[t].length; k++) {
                int u = k < 0 ? t : neighbors[t][k];
                if (opp[u] == 0) continue;
                if (sourceStamp[u] != stamp) {
                    sourceStamp[u] = stamp;
                    sourceMask[u] = 0;
                    sourceHouse[sources++] = u;
                }
                sourceMask[u] |= 1 << i;
            }
        }
        int totalNeed = 0, totalSupply = 0;
        for (int i = 0; i < m; i++) totalNeed += ours[targets[i]] + 1;
        for (int k = 0; k < sources; k++) totalSupply += opp[sourceHouse[k]];
        if (totalSupply < totalNeed) return false;
        for (int subset = 1; subset < (1 << m) - 1; subset++) {
            int need = 0;
            for (int i = 0; i < m; i++) {
                if ((subset >> i & 1) != 0) need += ours[targets[i]] + 1;
            }
            int supply = 0;
            for (int k = 0; k < sources; k++) {
                int u = sourceHouse[k];
                if ((sourceMask[u] & subset) != 0) supply += opp[u];
            }
            if (supply < need) return false;
        }
        return true;
    }

    static int nextHop(int from, int to) {
        for (int n : neighbors[from]) {
            if (dist[n][to] < dist[from][to]) return n;
        }
        return from;
    }

    // house rule: every neighbor where the attacker has strictly more units than the defender
    static boolean surroundedBy(int h, int[] attacker, int[] defender) {
        if (neighbors[h].length == 0) return false;
        for (int n : neighbors[h]) {
            if (attacker[n] <= defender[n]) return false;
        }
        return true;
    }

    // owner of a house: a player holding every neighbor, else the majority on the house itself
    static int ownerOf(int h, int[] first, int[] second) {
        if (surroundedBy(h, first, second)) return 0;
        if (surroundedBy(h, second, first)) return 1;
        if (first[h] > second[h]) return 0;
        if (second[h] > first[h]) return 1;
        return NONE;
    }

    static int[] housesWithUnits(int[] units) {
        int count = 0;
        for (int u : units) if (u > 0) count++;
        int[] houses = new int[count];
        for (int h = 0, k = 0; h < houseCount; h++) if (units[h] > 0) houses[k++] = h;
        return houses;
    }

    // front of each house for both players, and the gap between the starts
    static void computeFronts() {
        for (int p = 0; p < 2; p++) {
            frontOf[p] = new int[houseCount];
            for (int h = 0; h < houseCount; h++) {
                int mine = INF, theirs = INF;
                for (int s : startOf[p]) mine = Math.min(mine, dist[s][h]);
                for (int s : startOf[1 - p]) theirs = Math.min(theirs, dist[s][h]);
                frontOf[p][h] = mine >= INF || theirs >= INF ? 0 : mine - theirs;
                if (p == ME && theirs == 0 && mine < INF) startGap = Math.max(startGap, mine);
            }
        }
    }

    // the opening is over once the viewer owns enough of the center (sticky in the real game)
    static void computeOpening() {
        invasionDone = openingOver[viewer];
        if (invasionDone) return;
        int center = 0, owned = 0;
        for (Triangle t : triangles) {
            if (!isCenter(t)) continue;
            center++;
            if (t.owner == ME) owned++;
        }
        invasionDone = turn > INVASION_MAX_TURN || owned >= CENTER_SHARE * center;
    }

    // average front of the corners: negative on the viewer's side, around zero in the middle of the map
    static double zone(Triangle t) {
        return (front[t.corners[0]] + front[t.corners[1]] + front[t.corners[2]]) / 3.0;
    }

    static boolean isRear(Triangle t) {
        return zone(t) < -CENTER_WIDTH;
    }

    static boolean isCenter(Triangle t) {
        return Math.abs(zone(t)) <= CENTER_WIDTH;
    }

    static int total(int[] units) {
        int sum = 0;
        for (int u : units) sum += u;
        return sum;
    }

    static void computeDistances() {
        dist = new int[houseCount][houseCount];
        byDistance = new int[houseCount][];
        int[] queue = new int[houseCount];
        for (int start = 0; start < houseCount; start++) {
            int[] d = dist[start];
            Arrays.fill(d, INF);
            d[start] = 0;
            int head = 0, tail = 0;
            queue[tail++] = start;
            while (head < tail) {
                int h = queue[head++];
                for (int n : neighbors[h]) {
                    if (d[n] == INF) {
                        d[n] = d[h] + 1;
                        queue[tail++] = n;
                    }
                }
            }
            // BFS order is sorted by distance, unreachable houses go last
            int[] order = Arrays.copyOf(queue, houseCount);
            int k = tail;
            for (int h = 0; h < houseCount; h++) if (d[h] == INF) order[k++] = h;
            byDistance[start] = order;
        }
    }

    static int intProperty(String name, int fallback) {
        try {
            String value = System.getProperty(name);
            return value == null ? fallback : Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    static double doubleProperty(String name, double fallback) {
        try {
            String value = System.getProperty(name);
            return value == null ? fallback : Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }
}
