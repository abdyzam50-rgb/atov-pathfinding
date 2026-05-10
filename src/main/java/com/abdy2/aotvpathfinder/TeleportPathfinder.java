package com.abdy2.aotvpathfinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class TeleportPathfinder {
    public enum MovementMode {
        HYBRID,
        WALK_ONLY,
        TELEPORT_ONLY
    }

    public enum TeleportMode {
        SHIFT_ONLY,
        HYBRID_TELEPORT,
        JUST_TELEPORT
    }

    private static final int MAX_GRAVITY_DROP = 24;
    private static final int CANDIDATE_MAX_DEPTH = 220;
    private static final int JUST_TELEPORT_MIN_AIR_CLEARANCE = 13;
    private static final double JUST_TELEPORT_FINAL_WALK_RADIUS = 3.0;
    private static final int AIR_CHAIN_SAFE_FALL_DROP = 8;

    private static final List<BlockPos> SHORT_OFFSETS = buildShortOffsets();
    private static final List<BlockPos> LONG_OFFSETS = buildLongOffsets();
    private static final BlockPos[] WALK_OFFSETS = new BlockPos[] {
        new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
        new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
        new BlockPos(1, 0, 1), new BlockPos(1, 0, -1),
        new BlockPos(-1, 0, 1), new BlockPos(-1, 0, -1)
    };

    private final CobaltWalkPathfinder cobaltWalkPathfinder = new CobaltWalkPathfinder();

    public record CandidatePath(
        List<TeleportHop> hops,
        MovementMode movementMode,
        TeleportMode teleportMode,
        boolean airChainEnabled,
        boolean reachedGoal,
        double bestDistanceSq
    ) {}

    public List<TeleportHop> findPath(
        ClientPlayerEntity player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        MovementMode mode,
        TeleportMode teleportMode,
        boolean allowAirChain
    ) {
        if (start.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
            return List.of();
        }

        MovementMode resolvedMode = mode == null ? MovementMode.HYBRID : mode;
        TeleportMode resolvedTeleportMode = teleportMode == null ? TeleportMode.HYBRID_TELEPORT : teleportMode;
        int distance = (int) Math.sqrt(start.getSquaredDistance(goal));
        SearchResult bestFailed = SearchResult.empty();

        if (allowAirChain && resolvedTeleportMode != TeleportMode.SHIFT_ONLY) {
            SearchResult airChain = searchDirectAirChain(player, start, goal, availableMana, resolvedTeleportMode);
            if (airChain.reachedGoal() || !airChain.hops().isEmpty()) {
                return airChain.hops();
            }
        }

        if (resolvedMode != MovementMode.WALK_ONLY) {
            int[] mixedBudgets = new int[] {
                Math.max(12000, Math.min(45000, distance * 70)),
                Math.max(18000, Math.min(70000, distance * 95)),
                Math.max(26000, Math.min(100000, distance * 125))
            };
            for (int budget : mixedBudgets) {
                SearchResult mixed = searchOnCustomNodeGraph(player, start, goal, availableMana, true, false, resolvedTeleportMode, budget);
                if (mixed.reachedGoal()) {
                    return mixed.hops();
                }
                bestFailed = chooseBetter(bestFailed, mixed);
            }
        }

        if (resolvedMode != MovementMode.TELEPORT_ONLY) {
            int[] walkBudgets = new int[] {
                Math.max(15000, Math.min(70000, distance * 90)),
                Math.max(26000, Math.min(120000, distance * 150))
            };
            for (int budget : walkBudgets) {
                SearchResult walkGraph = searchOnCustomNodeGraph(player, start, goal, -1, false, false, resolvedTeleportMode, budget);
                if (walkGraph.reachedGoal()) {
                    return walkGraph.hops();
                }
                bestFailed = chooseBetter(bestFailed, walkGraph);
            }

            int[] pureWalkBudgets = new int[] {
                Math.max(30000, Math.min(120000, distance * 140)),
                Math.max(50000, Math.min(180000, distance * 200))
            };
            for (int budget : pureWalkBudgets) {
                SearchResult pureWalk = searchPureWalk(player, start, goal, budget);
                if (pureWalk.reachedGoal()) {
                    return pureWalk.hops();
                }
                bestFailed = chooseBetter(bestFailed, pureWalk);
            }
        }

        return bestFailed.hops();
    }


    public List<CandidatePath> enumerateCandidatePaths(
        ClientPlayerEntity player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        int maxPaths,
        int maxExpansions
    ) {
        List<CandidatePath> out = new ArrayList<>();
        Set<String> unique = new HashSet<>();

        MovementMode[] movementModes = new MovementMode[] {
            MovementMode.HYBRID,
            MovementMode.WALK_ONLY,
            MovementMode.TELEPORT_ONLY
        };
        TeleportMode[] teleportModes = new TeleportMode[] {
            TeleportMode.HYBRID_TELEPORT,
            TeleportMode.SHIFT_ONLY,
            TeleportMode.JUST_TELEPORT
        };
        boolean[] airOptions = new boolean[] { false, true };

        int manaBase = availableMana > 0 ? availableMana : 4000;
        int[] manaVariants = new int[] {
            manaBase,
            (int) (manaBase * 0.75),
            (int) (manaBase * 0.5),
            (int) (manaBase * 0.25),
            -1
        };

        int attempts = 0;
        int maxAttempts = Math.max(18, Math.min(48, maxPaths * 2));

        for (MovementMode movementMode : movementModes) {
            for (TeleportMode teleportMode : teleportModes) {
                for (boolean airChain : airOptions) {
                    if (airChain && teleportMode == TeleportMode.SHIFT_ONLY) {
                        continue;
                    }
                    for (int mana : manaVariants) {
                        if (attempts++ >= maxAttempts) {
                            return out;
                        }
                        List<TeleportHop> hops = findPath(player, start, goal, mana, movementMode, teleportMode, airChain);
                        if (hops.isEmpty()) {
                            continue;
                        }

                        String sig = pathSignature(hops);
                        if (!unique.add(sig)) {
                            continue;
                        }

                        boolean reachedGoal = pathReachesGoal(start, hops, goal);
                        BlockPos end = hops.get(hops.size() - 1).landing();
                        double bestDistanceSq = end.getSquaredDistance(goal);
                        out.add(new CandidatePath(hops, movementMode, teleportMode, airChain, reachedGoal, bestDistanceSq));

                        if (out.size() >= Math.max(1, maxPaths)) {
                            return out;
                        }
                    }
                }
            }
        }

        return out;
    }

    private boolean pathReachesGoal(BlockPos start, List<TeleportHop> hops, BlockPos goal) {
        BlockPos end = hops.isEmpty() ? start : hops.get(hops.size() - 1).landing();
        return end.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS);
    }

    private String pathSignature(List<TeleportHop> hops) {
        StringBuilder sb = new StringBuilder(hops.size() * 16);
        for (TeleportHop hop : hops) {
            BlockPos p = hop.landing();
            sb.append(hop.type().name()).append(':')
                .append(p.getX()).append(',')
                .append(p.getY()).append(',')
                .append(p.getZ()).append(';');
        }
        return sb.toString();
    }

    private SearchResult searchDirectAirChain(
        ClientPlayerEntity player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        TeleportMode teleportMode
    ) {
        List<TeleportHop> hops = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos current = start;
        BlockPos previous = null;
        Vec3d lockedDirection = null;
        seen.add(current);

        double horizontalStartDist = Math.hypot(start.getX() - goal.getX(), start.getZ() - goal.getZ());
        int cruiseLift = Math.max(36, Math.min(180, (int) (horizontalStartDist * 1.1)));
        int cruiseY = Math.max(start.getY(), goal.getY()) + cruiseLift;
        if (goal.getY() - start.getY() > 20) {
            cruiseY += Math.min(80, goal.getY() - start.getY());
        }
        int maxHopsByMana = availableMana > 0 ? Math.max(1, availableMana / AotvConfig.TRANSMISSION_MANA) : 160;
        int maxHops = Math.min(260, Math.max(30, maxHopsByMana));
        double bestDistSq = current.getSquaredDistance(goal);
        BlockPos bestPos = current;

        for (int i = 0; i < maxHops; i++) {
            if (current.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
                return new SearchResult(hops, true, 0.0);
            }

            BlockPos next = pickDirectAirChainStep(player, current, goal, teleportMode, seen, previous, lockedDirection, cruiseY);
            if (next == null) {
                break;
            }

            hops.add(new TeleportHop(next, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA));
            Vec3d hopVec = Vec3d.ofCenter(next).subtract(Vec3d.ofCenter(current));
            double hopLen = Math.sqrt(hopVec.x * hopVec.x + hopVec.y * hopVec.y + hopVec.z * hopVec.z);
            if (hopLen > 0.001) {
                Vec3d hopDir = hopVec.multiply(1.0 / hopLen);
                if (lockedDirection == null) {
                    lockedDirection = hopDir;
                } else if (lockedDirection.dotProduct(hopDir) < 0.965) {
                    lockedDirection = hopDir;
                }
            }

            previous = current;
            current = next;
            seen.add(current);

            double distSq = current.getSquaredDistance(goal);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestPos = current;
            }
        }

        BlockPos safeHandoff = settleByGravityWithLimit(player, bestPos, AIR_CHAIN_SAFE_FALL_DROP);
        if (safeHandoff == null) {
            safeHandoff = settleByGravityWithLimit(player, current, AIR_CHAIN_SAFE_FALL_DROP);
        }
        if (safeHandoff == null) {
            safeHandoff = bestPos;
        }

        SearchResult walkFinish = searchPureWalk(player, safeHandoff, goal, 28000);
        if (walkFinish.reachedGoal() || !walkFinish.hops().isEmpty()) {
            List<TeleportHop> combined = new ArrayList<>(hops);
            combined.addAll(walkFinish.hops());
            return new SearchResult(
                combined,
                walkFinish.reachedGoal(),
                Math.min(bestDistSq, walkFinish.bestDistanceSq())
            );
        }

        return new SearchResult(hops, false, bestDistSq);
    }

    private BlockPos pickDirectAirChainStep(
        ClientPlayerEntity player,
        BlockPos from,
        BlockPos goal,
        TeleportMode teleportMode,
        Set<BlockPos> seen,
        BlockPos previousFrom,
        Vec3d lockedDirection,
        int cruiseY
    ) {
        double fromDist = Math.sqrt(from.getSquaredDistance(goal));
        boolean blockedToGoal = !hasTeleportCorridorClear(player, from, goal);
        Vec3d toGoal = Vec3d.ofCenter(goal).subtract(Vec3d.ofCenter(from));
        Vec3d goalDir = toGoal.lengthSquared() > 0.001 ? toGoal.normalize() : new Vec3d(0.0, 0.0, 0.0);
        double horizontalDist = Math.hypot(from.getX() - goal.getX(), from.getZ() - goal.getZ());
        boolean descendPhase = horizontalDist <= 26.0;

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (BlockPos offset : SHORT_OFFSETS) {
            BlockPos candidate = from.add(offset);
            if (seen.contains(candidate)) {
                continue;
            }

            if (!isPassableForPlayer(player, candidate) || !isPassableForPlayer(player, candidate.up())) {
                continue;
            }

            if (teleportMode == TeleportMode.JUST_TELEPORT && !hasVerticalClearance(player, candidate, JUST_TELEPORT_MIN_AIR_CLEARANCE)) {
                continue;
            }

            if (!hasTeleportCorridorClear(player, from, candidate)) {
                continue;
            }

            double candidateDist = Math.sqrt(candidate.getSquaredDistance(goal));
            if (descendPhase && from.getY() - goal.getY() > 4 && offset.getY() > -2) {
                continue;
            }
            boolean climbPhase = !descendPhase && from.getY() + 6 < cruiseY;
            if (climbPhase && offset.getY() <= 0) {
                continue;
            }

            if (blockedToGoal && offset.getY() >= 2) {
                if (candidateDist > fromDist + 14.0) {
                    continue;
                }
            } else if (climbPhase && offset.getY() > 0) {
                if (candidateDist > fromDist + 16.0) {
                    continue;
                }
            } else if (candidateDist > fromDist + 0.8) {
                continue;
            }

            Vec3d step = Vec3d.ofCenter(candidate).subtract(Vec3d.ofCenter(from));
            double stepLen = Math.sqrt(step.x * step.x + step.y * step.y + step.z * step.z);
            Vec3d stepDir = stepLen > 0.001 ? step.multiply(1.0 / stepLen) : new Vec3d(0.0, 0.0, 0.0);
            double alignment = step.lengthSquared() > 0.001 ? goalDir.dotProduct(stepDir) : 0.0;

            double lockPenalty = 0.0;
            if (lockedDirection != null && stepLen > 0.001) {
                double lockAlign = lockedDirection.dotProduct(stepDir);
                lockPenalty = Math.max(0.0, 1.0 - lockAlign) * 7.5;
                if (lockAlign < 0.992 && candidateDist > fromDist - 0.55) {
                    continue;
                }
            }

            double lateralPenalty = Math.max(0.0, 1.0 - alignment) * 2.4;

            double turnPenalty = 0.0;
            if (previousFrom != null) {
                Vec3d prevStep = Vec3d.ofCenter(from).subtract(Vec3d.ofCenter(previousFrom));
                double prevLen = Math.sqrt(prevStep.x * prevStep.x + prevStep.y * prevStep.y + prevStep.z * prevStep.z);
                if (prevLen > 0.001 && stepLen > 0.001) {
                    Vec3d prevDir = prevStep.multiply(1.0 / prevLen);
                    double continuity = prevDir.dotProduct(stepDir);
                    turnPenalty = Math.max(0.0, 1.0 - continuity) * 2.2;
                }
            }

            double score = candidateDist - (alignment * 4.35) - Math.max(0.0, offset.getY()) * 0.10 + lateralPenalty + turnPenalty + lockPenalty;

            if (!descendPhase) {
                score += Math.max(0, cruiseY - candidate.getY()) * 0.22;
                if (offset.getY() < 0) {
                    score += 2.4;
                }
                if (offset.getY() > 0) {
                    score -= Math.min(2.6, offset.getY() * 0.42);
                }
            } else {
                score += Math.abs(candidate.getY() - goal.getY()) * 0.08;
                if (offset.getY() < 0) {
                    score -= Math.min(2.1, Math.abs(offset.getY()) * 0.35);
                }
            }

            if (blockedToGoal && offset.getY() > 0) {
                score -= Math.min(2.0, offset.getY() * 0.30);
            }

            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null && blockedToGoal) {
            for (int dy = 12; dy >= 6; dy--) {
                BlockPos climb = from.up(dy);
                if (seen.contains(climb)) {
                    continue;
                }
                if (!isPassableForPlayer(player, climb) || !isPassableForPlayer(player, climb.up())) {
                    continue;
                }
                if (teleportMode == TeleportMode.JUST_TELEPORT && !hasVerticalClearance(player, climb, JUST_TELEPORT_MIN_AIR_CLEARANCE)) {
                    continue;
                }
                if (!hasTeleportCorridorClear(player, from, climb)) {
                    continue;
                }
                best = climb;
                break;
            }
        }

        return best;
    }

    private SearchResult searchOnCustomNodeGraph(
        ClientPlayerEntity player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        boolean includeTeleports,
        boolean allowAirNormalTeleports,
        TeleportMode teleportMode,
        int maxExpansions
    ) {
        int graphNodeBudget = includeTeleports
            ? Math.max(1200, Math.min(7000, maxExpansions / 4))
            : Math.max(2500, Math.min(16000, maxExpansions / 2));

        Map<BlockPos, GraphNode> graph = buildCustomNodeGraph(player, start, goal, includeTeleports, allowAirNormalTeleports, teleportMode, maxExpansions, graphNodeBudget);

        GraphNode startNode = graph.get(start);
        if (startNode == null) {
            return SearchResult.empty();
        }

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fScore));
        Map<GraphNode, SearchNode> visited = new HashMap<>();
        Set<GraphNode> closed = new HashSet<>();

        SearchNode first = new SearchNode(startNode, null, 0.0, heuristic(start, goal), 0, TeleportHop.HopType.WALK, 0);
        open.add(first);
        visited.put(startNode, first);

        SearchNode best = first;
        double bestDistSq = start.getSquaredDistance(goal);

        int expansions = 0;
        while (!open.isEmpty() && expansions < maxExpansions) {
            SearchNode current = open.poll();
            if (closed.contains(current.node)) {
                continue;
            }
            closed.add(current.node);
            expansions++;

            double distSq = current.node.pos.getSquaredDistance(goal);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = current;
            }

            if (current.node.pos.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
                return new SearchResult(backtrack(current), true, 0.0);
            }

            for (GraphEdge edge : current.node.edges) {
                if (closed.contains(edge.to)) {
                    continue;
                }

                int nextManaSpent = current.manaSpent + edge.manaCost;
                if (availableMana > 0 && nextManaSpent > availableMana) {
                    continue;
                }

                double nextG = current.gScore + edge.travelCost;
                SearchNode known = visited.get(edge.to);
                if (known != null && nextG >= known.gScore) {
                    continue;
                }

                SearchNode next = new SearchNode(
                    edge.to,
                    current,
                    nextG,
                    nextG + heuristic(edge.to.pos, goal),
                    nextManaSpent,
                    edge.type,
                    edge.manaCost
                );

                visited.put(edge.to, next);
                open.add(next);
            }
        }

        return new SearchResult(backtrack(best), false, bestDistSq);
    }

    private Map<BlockPos, GraphNode> buildCustomNodeGraph(
        ClientPlayerEntity player,
        BlockPos start,
        BlockPos goal,
        boolean includeTeleports,
        boolean allowAirNormalTeleports,
        TeleportMode teleportMode,
        int maxExpansions,
        int maxNodes
    ) {
        Map<BlockPos, GraphNode> graph = new HashMap<>();
        ArrayDeque<GraphNode> queue = new ArrayDeque<>();
        Set<BlockPos> expanded = new HashSet<>();

        GraphNode startNode = new GraphNode(start);
        graph.put(start, startNode);
        queue.add(startNode);

        int expansions = 0;
        while (!queue.isEmpty() && expansions < maxExpansions && graph.size() < maxNodes) {
            GraphNode current = queue.poll();
            if (!expanded.add(current.pos)) {
                continue;
            }
            expansions++;

            for (Neighbor neighbor : neighbors(player, current.pos, includeTeleports, allowAirNormalTeleports, teleportMode, goal)) {
                GraphNode to = graph.get(neighbor.pos);
                boolean isNew = false;
                if (to == null) {
                    to = new GraphNode(neighbor.pos);
                    graph.put(neighbor.pos, to);
                    isNew = true;
                }

                current.edges.add(new GraphEdge(to, neighbor.type, neighbor.manaCost, neighbor.travelCost));

                if (isNew && shouldExpandNode(start, goal, to.pos, includeTeleports)) {
                    queue.add(to);
                }
            }
        }

        if (!graph.containsKey(goal) && isSafeStanding(player, goal)) {
            graph.put(goal, new GraphNode(goal));
        }

        return graph;
    }

    private boolean shouldExpandNode(BlockPos start, BlockPos goal, BlockPos candidate, boolean includeTeleports) {
        if (!includeTeleports) {
            return true;
        }

        double startToGoal = Math.sqrt(start.getSquaredDistance(goal));
        double startToCandidate = Math.sqrt(start.getSquaredDistance(candidate));
        double candidateToGoal = Math.sqrt(candidate.getSquaredDistance(goal));
        return startToCandidate + candidateToGoal <= startToGoal + 70.0;
    }

    private Collection<Neighbor> neighbors(ClientPlayerEntity player, BlockPos from, boolean includeTeleports, boolean allowAirNormalTeleports, TeleportMode teleportMode, BlockPos goal) {
        List<Neighbor> out = new ArrayList<>(SHORT_OFFSETS.size() + LONG_OFFSETS.size() + 16);
        double fromGoalSq = from.getSquaredDistance(goal);

        if (includeTeleports) {
            boolean allowNormal = teleportMode != TeleportMode.SHIFT_ONLY;
            boolean allowShift = teleportMode != TeleportMode.JUST_TELEPORT;

            if (allowNormal) {
                for (BlockPos offset : SHORT_OFFSETS) {
                    if (teleportMode == TeleportMode.JUST_TELEPORT && offset.getY() < 2) {
                        continue;
                    }

                    BlockPos aimPoint = from.add(offset);
                    if (teleportMode == TeleportMode.JUST_TELEPORT && !hasVerticalClearance(player, aimPoint, JUST_TELEPORT_MIN_AIR_CLEARANCE)) {
                        continue;
                    }
                    if (!simulateTransmissionClear(player, from, aimPoint)) {
                        continue;
                    }

                    BlockPos landing = settleByGravity(player, aimPoint);
                    if (landing == null || !hasLineOfSight(player, from, landing)) {
                        if (allowAirNormalTeleports && offset.getY() >= -1 && isAirWaypointValid(player, from, aimPoint) && (teleportMode != TeleportMode.JUST_TELEPORT || hasVerticalClearance(player, aimPoint, JUST_TELEPORT_MIN_AIR_CLEARANCE))) {
                            out.add(new Neighbor(aimPoint, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA, 2.9));
                        }
                        continue;
                    }

                    double gravityPenalty = Math.max(0, aimPoint.getY() - landing.getY()) * 0.03;
                    double landingGoalSq = landing.getSquaredDistance(goal);
                    if (landingGoalSq > fromGoalSq + 200.0 && !landing.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
                        continue;
                    }
                    out.add(new Neighbor(landing, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA, 2.45 + gravityPenalty));
                }
            }

            if (allowShift) {
                for (BlockPos offset : LONG_OFFSETS) {
                    BlockPos aimPoint = from.add(offset);
                    if (!hasLineOfSight(player, from, aimPoint)) {
                        continue;
                    }

                    BlockPos landing = settleByGravity(player, aimPoint);
                    if (landing == null || !hasLineOfSight(player, from, landing)) {
                        continue;
                    }

                    double gravityPenalty = Math.max(0, aimPoint.getY() - landing.getY()) * 0.03;
                    double landingGoalSq = landing.getSquaredDistance(goal);
                    if (landingGoalSq > fromGoalSq + 200.0 && !landing.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
                        continue;
                    }
                    out.add(new Neighbor(landing, TeleportHop.HopType.SHIFT, AotvConfig.ETHERWARP_MANA, 3.2 + gravityPenalty));
                }
            }
        }

        double walkTravelCost = includeTeleports ? 2.1 : 1.35;
        for (BlockPos walkOffset : WALK_OFFSETS) {
            BlockPos base = from.add(walkOffset);
            for (int y = -1; y <= 1; y++) {
                BlockPos candidate = base.add(0, y, 0);
                if (!isWalkTransitionValid(player, from, candidate)) {
                    continue;
                }
                if (teleportMode == TeleportMode.JUST_TELEPORT && !candidate.isWithinDistance(goal, JUST_TELEPORT_FINAL_WALK_RADIUS)) {
                    continue;
                }
                out.add(new Neighbor(candidate, TeleportHop.HopType.WALK, 0, walkTravelCost + (y > 0 ? 0.15 : 0.0)));
            }
        }

        if (includeTeleports && teleportMode != TeleportMode.SHIFT_ONLY) {
            for (BlockPos walkOffset : WALK_OFFSETS) {
                BlockPos edge = from.add(walkOffset);
                if (!isPassableForPlayer(player, edge) || !isPassableForPlayer(player, edge.up()) || isSafeStanding(player, edge)) {
                    continue;
                }

                for (int drop = 2; drop <= 8; drop++) {
                    BlockPos landing = edge.down(drop);
                    if (!isSafeStanding(player, landing)) {
                        continue;
                    }
                    if (!hasTeleportCorridorClear(player, from, landing)) {
                        continue;
                    }
                    out.add(new Neighbor(landing, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA, 2.8 + drop * 0.08));
                    break;
                }
            }
        }

        return out;
    }

    private boolean simulateTransmissionClear(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        return hasTeleportCorridorClear(player, from, to);
    }

    private boolean isAirWaypointValid(ClientPlayerEntity player, BlockPos from, BlockPos pos) {
        if (!hasTeleportCorridorClear(player, from, pos)) {
            return false;
        }
        return isPassableForPlayer(player, pos) && isPassableForPlayer(player, pos.up());
    }

    private boolean hasLineOfSight(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        return hasTeleportCorridorClear(player, from, to);
    }

    private boolean hasTeleportCorridorClear(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        return isRayClear(player, from, to, 0.12)
            && isRayClear(player, from, to, 0.92)
            && isRayClear(player, from, to, 1.62);
    }

    private boolean isRayClear(ClientPlayerEntity player, BlockPos from, BlockPos to, double yOffset) {
        Vec3d start = new Vec3d(from.getX() + 0.5, from.getY() + yOffset, from.getZ() + 0.5);
        Vec3d end = new Vec3d(to.getX() + 0.5, to.getY() + yOffset, to.getZ() + 0.5);

        HitResult colliderHit = player.getEntityWorld().raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (colliderHit.getType() != HitResult.Type.MISS) {
            return false;
        }

        HitResult outlineHit = player.getEntityWorld().raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return outlineHit.getType() == HitResult.Type.MISS;
    }

    private BlockPos settleByGravity(ClientPlayerEntity player, BlockPos start) {
        return settleByGravityWithLimit(player, start, MAX_GRAVITY_DROP);
    }

    private BlockPos settleByGravityWithLimit(ClientPlayerEntity player, BlockPos start, int maxDrop) {
        BlockPos cursor = start;
        for (int drop = 0; drop <= maxDrop; drop++) {
            if (isSafeStanding(player, cursor)) {
                return cursor;
            }
            if (!isPassableForPlayer(player, cursor) || !isPassableForPlayer(player, cursor.up())) {
                return null;
            }
            cursor = cursor.down();
        }
        return null;
    }

    private boolean isPassableForPlayer(ClientPlayerEntity player, BlockPos pos) {
        return player.getEntityWorld().getBlockState(pos).isAir();
    }

    private boolean isWalkPassable(ClientPlayerEntity player, BlockPos pos) {
        return player.getEntityWorld().getBlockState(pos)
            .getCollisionShape(player.getEntityWorld(), pos)
            .isEmpty();
    }

    private boolean isWalkSafeStanding(ClientPlayerEntity player, BlockPos pos) {
        BlockState feet = player.getEntityWorld().getBlockState(pos);
        BlockState head = player.getEntityWorld().getBlockState(pos.up());
        BlockState below = player.getEntityWorld().getBlockState(pos.down());
        return feet.getCollisionShape(player.getEntityWorld(), pos).isEmpty()
            && head.getCollisionShape(player.getEntityWorld(), pos.up()).isEmpty()
            && below.isSolidBlock(player.getEntityWorld(), pos.down());
    }

    private boolean hasVerticalClearance(ClientPlayerEntity player, BlockPos base, int requiredAirBlocks) {
        for (int i = 0; i < requiredAirBlocks; i++) {
            if (!isPassableForPlayer(player, base.up(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isWalkTransitionValid(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        if (!isWalkSafeStanding(player, to)) {
            return false;
        }

        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dy = to.getY() - from.getY();
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || Math.abs(dy) > 1) {
            return false;
        }

        if (dy > 0) {
            if (!player.getEntityWorld().getBlockState(from.up(2)).isAir()) {
                return false;
            }
            if (!hasJumpArcClear(player, from, to)) {
                return false;
            }
        }

        if (dx != 0 && dz != 0) {
            BlockPos sideX = from.add(dx, 0, 0);
            BlockPos sideZ = from.add(0, 0, dz);
            boolean sideXClear = isWalkPassable(player, sideX) && isWalkPassable(player, sideX.up());
            boolean sideZClear = isWalkPassable(player, sideZ) && isWalkPassable(player, sideZ.up());

            if (dy > 0) {
                if (!sideXClear && !sideZClear) {
                    return false;
                }
            } else {
                if (!sideXClear || !sideZClear) {
                    return false;
                }
            }
        }

        return hasWalkCorridorClear(player, from, to);
    }

    private boolean hasWalkCorridorClear(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        Vec3d fromFeet = Vec3d.ofCenter(from).add(0.0, 0.05, 0.0);
        Vec3d toFeet = Vec3d.ofCenter(to).add(0.0, 0.05, 0.0);
        Vec3d fromHead = Vec3d.ofCenter(from).add(0.0, 1.05, 0.0);
        Vec3d toHead = Vec3d.ofCenter(to).add(0.0, 1.05, 0.0);

        HitResult feetHit = player.getEntityWorld().raycast(new RaycastContext(
            fromFeet,
            toFeet,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (feetHit.getType() != HitResult.Type.MISS) {
            return false;
        }

        HitResult headHit = player.getEntityWorld().raycast(new RaycastContext(
            fromHead,
            toHead,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return headHit.getType() == HitResult.Type.MISS;
    }

    private boolean hasJumpArcClear(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        Vec3d upStart = Vec3d.ofCenter(from).add(0.0, 0.05, 0.0);
        Vec3d upEnd = upStart.add(0.0, 1.0, 0.0);
        HitResult vertical = player.getEntityWorld().raycast(new RaycastContext(
            upStart,
            upEnd,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (vertical.getType() != HitResult.Type.MISS) {
            return false;
        }

        Vec3d hStart = Vec3d.ofCenter(from).add(0.0, 1.05, 0.0);
        Vec3d hEnd = Vec3d.ofCenter(to).add(0.0, 1.05, 0.0);
        HitResult horizontal = player.getEntityWorld().raycast(new RaycastContext(
            hStart,
            hEnd,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return horizontal.getType() == HitResult.Type.MISS;
    }

    private boolean isSafeStanding(ClientPlayerEntity player, BlockPos pos) {
        BlockState feet = player.getEntityWorld().getBlockState(pos);
        BlockState head = player.getEntityWorld().getBlockState(pos.up());
        BlockState below = player.getEntityWorld().getBlockState(pos.down());
        return feet.isAir() && head.isAir() && below.isSolidBlock(player.getEntityWorld(), pos.down());
    }

    private double heuristic(BlockPos from, BlockPos goal) {
        double d = Math.sqrt(from.getSquaredDistance(goal));
        return d / AotvConfig.ETHERWARP_RANGE;
    }

    private List<TeleportHop> backtrack(SearchNode node) {
        List<TeleportHop> reversed = new ArrayList<>();
        SearchNode cursor = node;
        while (cursor != null && cursor.parent != null) {
            reversed.add(new TeleportHop(cursor.node.pos, cursor.type, cursor.manaCost));
            cursor = cursor.parent;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private SearchResult searchPureWalk(ClientPlayerEntity player, BlockPos start, BlockPos goal, int maxExpansions) {
        CobaltWalkPathfinder.Result walk = cobaltWalkPathfinder.findPath(
            player,
            start,
            goal,
            maxExpansions,
            AotvConfig.GOAL_REACHED_RADIUS
        );

        List<TeleportHop> hops = new ArrayList<>(walk.path().size());
        for (BlockPos pos : walk.path()) {
            hops.add(new TeleportHop(pos, TeleportHop.HopType.WALK, 0));
        }

        return new SearchResult(hops, walk.reachedGoal(), walk.bestDistanceSq());
    }

    private SearchResult chooseBetter(SearchResult a, SearchResult b) {
        if (b.reachedGoal() && !a.reachedGoal()) {
            return b;
        }
        if (a.reachedGoal() && !b.reachedGoal()) {
            return a;
        }
        if (b.bestDistanceSq() < a.bestDistanceSq()) {
            return b;
        }
        if (a.hops().isEmpty() && !b.hops().isEmpty()) {
            return b;
        }
        return a;
    }

    private static List<BlockPos> buildShortOffsets() {
        List<BlockPos> out = new ArrayList<>();
        int max = AotvConfig.TRANSMISSION_RANGE;
        for (int dx = -max; dx <= max; dx += 2) {
            for (int dz = -max; dz <= max; dz += 2) {
                for (int dy = -20; dy <= 38; dy++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist < 4 || dist > max) {
                        continue;
                    }
                    out.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        return out;
    }

    private static List<BlockPos> buildLongOffsets() {
        List<BlockPos> out = new ArrayList<>();
        int max = AotvConfig.ETHERWARP_RANGE;
        List<Integer> distances = new ArrayList<>();
        for (int d = AotvConfig.TRANSMISSION_RANGE + 1; d <= max; d += 4) {
            distances.add(d);
        }
        if (!distances.contains(max)) {
            distances.add(max);
        }

        for (int pitchDeg : new int[] { -30, -15, 0, 15, 30 }) {
            float pitch = (float) Math.toRadians(pitchDeg);
            double cp = Math.cos(pitch);
            double sp = Math.sin(pitch);

            for (int yawDeg = 0; yawDeg < 360; yawDeg += 15) {
                float yaw = (float) Math.toRadians(yawDeg);
                double cy = Math.cos(yaw);
                double sy = Math.sin(yaw);

                Vec3d unit = new Vec3d(-sy * cp, -sp, cy * cp);
                for (int distance : distances) {
                    BlockPos offset = BlockPos.ofFloored(unit.multiply(distance));
                    if (offset.getManhattanDistance(BlockPos.ORIGIN) < AotvConfig.TRANSMISSION_RANGE + 2) {
                        continue;
                    }
                    out.add(offset);
                }
            }
        }

        return out.stream().distinct().toList();
    }

    private record Neighbor(BlockPos pos, TeleportHop.HopType type, int manaCost, double travelCost) {}
    private record GraphEdge(GraphNode to, TeleportHop.HopType type, int manaCost, double travelCost) {}
    private record SearchResult(List<TeleportHop> hops, boolean reachedGoal, double bestDistanceSq) {
        private static SearchResult empty() {
            return new SearchResult(Collections.emptyList(), false, Double.POSITIVE_INFINITY);
        }
    }

    private static final class GraphNode {
        private final BlockPos pos;
        private final List<GraphEdge> edges = new ArrayList<>();

        private GraphNode(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final class SearchNode {
        private final GraphNode node;
        private final SearchNode parent;
        private final double gScore;
        private final double fScore;
        private final int manaSpent;
        private final TeleportHop.HopType type;
        private final int manaCost;

        private SearchNode(
            GraphNode node,
            SearchNode parent,
            double gScore,
            double fScore,
            int manaSpent,
            TeleportHop.HopType type,
            int manaCost
        ) {
            this.node = node;
            this.parent = parent;
            this.gScore = gScore;
            this.fScore = fScore;
            this.manaSpent = manaSpent;
            this.type = type;
            this.manaCost = manaCost;
        }
    }
}
