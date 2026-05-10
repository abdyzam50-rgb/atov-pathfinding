package com.abdy2.aotvpathfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

final class CobaltWalkPathfinder {
    private static final int MAX_SAFE_WALK_DROP = 2;
    private static final BlockPos[] NEIGHBOR_OFFSETS = new BlockPos[] {
        new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
        new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
        new BlockPos(1, 0, 1), new BlockPos(1, 0, -1),
        new BlockPos(-1, 0, 1), new BlockPos(-1, 0, -1),
        new BlockPos(0, 1, 0), new BlockPos(0, -1, 0)
    };

    Result findPath(ClientPlayerEntity player, BlockPos start, BlockPos goal, int maxIterations, double goalRadius) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<BlockPos, Node> visited = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node root = new Node(start, null, 0.0, heuristic(start, goal));
        open.add(root);
        visited.put(start, root);

        Node best = root;
        double bestDistSq = start.getSquaredDistance(goal);
        int iterations = 0;

        while (!open.isEmpty() && iterations < maxIterations) {
            Node current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            iterations++;

            double distSq = current.pos().getSquaredDistance(goal);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = current;
            }

            if (current.pos().isWithinDistance(goal, goalRadius)) {
                return new Result(backtrack(current), true, 0.0);
            }

            for (BlockPos offset : NEIGHBOR_OFFSETS) {
                BlockPos candidate = current.pos().add(offset);
                if (closed.contains(candidate)) {
                    continue;
                }
                if (!isValidTransition(player, current.pos(), candidate)) {
                    continue;
                }

                double transitionCost = baseTransitionCost(current.pos(), candidate) + extraCost(player, current, candidate);
                double nextG = current.g() + Math.max(0.0, transitionCost);

                Node known = visited.get(candidate);
                if (known != null && nextG >= known.g()) {
                    continue;
                }

                Node next = new Node(candidate, current, nextG, nextG + heuristic(candidate, goal));
                visited.put(candidate, next);
                open.add(next);
            }
        }

        return new Result(backtrack(best), false, bestDistSq);
    }

    private List<BlockPos> backtrack(Node node) {
        List<BlockPos> reversed = new ArrayList<>();
        Node cursor = node;
        while (cursor != null && cursor.parent() != null) {
            reversed.add(cursor.pos());
            cursor = cursor.parent();
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private boolean isValidTransition(ClientPlayerEntity player, BlockPos prev, BlockPos pos) {
        NavigationPoint currentPoint = navigationPoint(player, pos);
        if (!currentPoint.traversable()) {
            return false;
        }

        NavigationPoint prevPoint = navigationPoint(player, prev);
        int dy = pos.getY() - prev.getY();
        int dx = pos.getX() - prev.getX();
        int dz = pos.getZ() - prev.getZ();

        if (dy > 1) {
            return false;
        }
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || Math.abs(dy) > 1) {
            return false;
        }

        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            BlockPos c1 = prev.add(dx, 0, 0);
            BlockPos c2 = prev.add(0, 0, dz);
            if (!navigationPoint(player, c1).traversable() || !navigationPoint(player, c2).traversable()) {
                return false;
            }
        }

        if (dy < 0) {
            return true;
        }
        if (dy > 0) {
            return prevPoint.hasFloor() || currentPoint.climbable();
        }
        return currentPoint.hasFloor() || prevPoint.hasFloor() || currentPoint.climbable() || prevPoint.climbable();
    }

    private double baseTransitionCost(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double extraCost(ClientPlayerEntity player, Node current, BlockPos nextPos) {
        NavigationPoint currentPoint = navigationPoint(player, nextPos);
        NavigationPoint prevPoint = navigationPoint(player, current.pos());
        double dy = currentPoint.floorLevel() - prevPoint.floorLevel();
        double add = 0.0;

        if (dy > 0.1) {
            add += 0.5 * dy;
        } else if (dy < -0.1) {
            add += 0.1 * Math.abs(dy);
        }

        double crampedPenalty = 0.0;
        for (int i = 2; i <= 3; i++) {
            BlockPos check = nextPos.up(i);
            if (player.getWorld().getBlockState(check).isSolidBlock(player.getWorld(), check)) {
                crampedPenalty += 0.1 / i;
            }
        }
        if (isSolidAt(player, nextPos.west()) || isSolidAt(player, nextPos.east()) || isSolidAt(player, nextPos.north()) || isSolidAt(player, nextPos.south())) {
            crampedPenalty += 0.05;
        }
        add += crampedPenalty;

        if (current.parent() != null) {
            BlockPos gp = current.parent().pos();
            double v1x = current.pos().getX() - gp.getX();
            double v1z = current.pos().getZ() - gp.getZ();
            double v2x = nextPos.getX() - current.pos().getX();
            double v2z = nextPos.getZ() - current.pos().getZ();
            double dot = v1x * v2x + v1z * v2z;
            double mag1 = Math.sqrt(v1x * v1x + v1z * v1z);
            double mag2 = Math.sqrt(v2x * v2x + v2z * v2z);
            if (mag1 > 0.1 && mag2 > 0.1) {
                double normDot = dot / (mag1 * mag2);
                if (normDot < 0.99) {
                    add += 0.05;
                }
            }
        }

        return add;
    }

    private boolean isSolidAt(ClientPlayerEntity player, BlockPos pos) {
        return player.getWorld().getBlockState(pos).isSolidBlock(player.getWorld(), pos);
    }

    private NavigationPoint navigationPoint(ClientPlayerEntity player, BlockPos pos) {
        BlockState feet = player.getWorld().getBlockState(pos);
        BlockState head = player.getWorld().getBlockState(pos.up());
        BlockState below = player.getWorld().getBlockState(pos.down());

        boolean traversable = canWalkThrough(player, feet, pos) && canWalkThrough(player, head, pos.up());
        boolean hasFloor = canWalkOn(player, below, pos.down());
        double floorLevel = floorLevel(player, pos);
        boolean climbable = feet.isOf(Blocks.LADDER) || feet.isOf(Blocks.VINE);

        return new NavigationPoint(traversable, hasFloor, floorLevel, climbable);
    }

    private boolean canWalkThrough(ClientPlayerEntity player, BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(player.getWorld(), pos);
        return shape.isEmpty();
    }

    private boolean canWalkOn(ClientPlayerEntity player, BlockState state, BlockPos pos) {
        return !state.getCollisionShape(player.getWorld(), pos).isEmpty();
    }

    private double floorLevel(ClientPlayerEntity player, BlockPos pos) {
        BlockPos belowPos = pos.down();
        BlockState below = player.getWorld().getBlockState(belowPos);
        VoxelShape shape = below.getCollisionShape(player.getWorld(), belowPos);
        if (shape.isEmpty()) {
            return belowPos.getY();
        }
        return belowPos.getY() + shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
    }

    private double heuristic(BlockPos from, BlockPos goal) {
        return Math.sqrt(from.getSquaredDistance(goal));
    }

    record Result(List<BlockPos> path, boolean reachedGoal, double bestDistanceSq) {}

    private record Node(BlockPos pos, Node parent, double g, double f) {}
    private record NavigationPoint(boolean traversable, boolean hasFloor, double floorLevel, boolean climbable) {}
}
