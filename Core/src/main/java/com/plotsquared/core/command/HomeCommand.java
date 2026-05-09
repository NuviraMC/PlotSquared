/*
 * PlotSquared, a land and world management plugin for Minecraft.
 * Copyright (C) IntellectualSites <https://intellectualsites.com>
 * Copyright (C) IntellectualSites team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.plotsquared.core.command;

import com.google.inject.Inject;
import com.plotsquared.core.PlotSquared;
import com.plotsquared.core.configuration.Settings;
import com.plotsquared.core.configuration.caption.TranslatableCaption;
import com.plotsquared.core.events.TeleportCause;
import com.plotsquared.core.permissions.Permission;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotArea;
import com.plotsquared.core.plot.PlotId;
import com.plotsquared.core.plot.world.PlotAreaManager;
import com.plotsquared.core.util.MathMan;
import com.plotsquared.core.util.PlayerManager;
import com.plotsquared.core.util.TabCompletions;
import com.plotsquared.core.util.query.PlotQuery;
import com.plotsquared.core.util.query.SortingStrategy;
import com.plotsquared.core.util.task.RunnableVal2;
import com.plotsquared.core.util.task.RunnableVal3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@CommandDeclaration(command = "home",
        permission = "plots.home",
        usage = "/plot home [<player> [<page>] | <page> | <alias> | <area;x;y> | <area> <x;y> | <area> <page>]",
        aliases = {"h"},
        requiredType = RequiredType.PLAYER,
        category = CommandCategory.TELEPORT)
public class HomeCommand extends Command {

    private final PlotAreaManager plotAreaManager;

    @Inject
    public HomeCommand(final @NonNull PlotAreaManager plotAreaManager) {
        super(MainCommand.getInstance(), true);
        this.plotAreaManager = plotAreaManager;
    }

    private void home(
            final @NonNull PlotPlayer<?> player,
            final @NonNull PlotQuery query, int page,
            final RunnableVal3<Command, Runnable, Runnable> confirm,
            final RunnableVal2<Command, CommandResult> whenDone
    ) {
        final List<Plot> unsorted = query.asList();
        if (unsorted.size() > 1) {
            query.whereBasePlot();
        }

        List<Plot> plots = query.asList();

        if (page < 0) {
            page = (plots.size() + 1) + page;
        }

        if (plots.isEmpty()) {
            player.sendMessage(TranslatableCaption.of("invalid.found_no_plots"));
            return;
        } else if (page > plots.size() || page < 1) {
            player.sendMessage(
                    TranslatableCaption.of("invalid.number_not_in_range"),
                    TagResolver.builder()
                            .tag("min", Tag.inserting(Component.text(1)))
                            .tag("max", Tag.inserting(Component.text(plots.size())))
                            .build()
            );
            return;
        }
        Plot plot = plots.get(page - 1);
        confirm.run(this, () -> plot.teleportPlayer(player, TeleportCause.COMMAND_HOME, result -> {
            if (result) {
                whenDone.run(this, CommandResult.SUCCESS);
            } else {
                whenDone.run(HomeCommand.this, CommandResult.FAILURE);
            }
        }), () -> whenDone.run(HomeCommand.this, CommandResult.FAILURE));
    }

    @Override
    public CompletableFuture<Boolean> execute(
            PlotPlayer<?> player, String[] args,
            RunnableVal3<Command, Runnable, Runnable> confirm,
            RunnableVal2<Command, CommandResult> whenDone
    ) throws CommandException {
        if (!player.hasPermission(Permission.PERMISSION_VISIT_OWNED) && !player.hasPermission(Permission.PERMISSION_HOME)) {
            player.sendMessage(
                    TranslatableCaption.of("permission.no_permission"),
                    TagResolver.resolver("node", Tag.inserting(Component.text(Permission.PERMISSION_VISIT_OWNED.toString())))
            );
            return CompletableFuture.completedFuture(false);
        }
        if (args.length > 2) {
            sendUsage(player);
            return CompletableFuture.completedFuture(false);
        }

        // /plot home                     -> eigene Plots, Seite 1
        // /plot home <zahl|last|n>       -> eigene Plots, Seite X
        // /plot home <spieler>           -> Plots des Spielers, Seite 1
        // /plot home <spieler> <zahl>    -> Plots des Spielers, Seite X
        // /plot home <alias>             -> Plot mit dem Alias
        // /plot home <area;x;y>          -> bestimmtes Plot
        // /plot home <area> <x;y>        -> bestimmtes Plot
        // /plot home <area> <page>       -> eigene Plots in Area, Seite X

        switch (args.length) {
            case 0 -> {
                // /p h  -> eigene Plots
                PlotQuery query = ownQuery(player);
                sortBySettings(query, player);
                home(player, query, 1, confirm, whenDone);
            }
            case 1 -> {
                final String arg0 = args[0];

                // Zahl oder "last"/"n" -> Seitennavigation auf eigenen Plots
                if (!isInvalidPageNr(arg0)) {
                    int page = getPageNr(arg0);
                    if (page == Integer.MIN_VALUE) {
                        sendInvalidPageNrMsg(player);
                        return CompletableFuture.completedFuture(false);
                    }
                    PlotQuery query = ownQuery(player);
                    sortBySettings(query, player);
                    home(player, query, page, confirm, whenDone);
                    break;
                }

                // Plot-ID (z.B. "world;1;2" oder "1;2")
                if (arg0.contains(";") || arg0.contains(",")) {
                    final Plot fromId = Plot.getPlotFromString(player, arg0, false);
                    if (fromId != null && fromId.isOwner(player.getUUID())) {
                        home(player, PlotQuery.newQuery().withPlot(fromId), 1, confirm, whenDone);
                        break;
                    }
                }

                // Plot-Area (z.B. "world")
                PlotArea plotArea = this.plotAreaManager.getPlotAreaByString(arg0);
                if (plotArea != null) {
                    PlotQuery query = ownQuery(player).inArea(plotArea);
                    sortBySettings(query, player);
                    home(player, query, 1, confirm, whenDone);
                    break;
                }

                // Spielername -> Plots dieses Spielers anzeigen (wie /p v <spieler>)
                PlotSquared.get().getImpromptuUUIDPipeline().getSingle(arg0, (uuid, throwable) -> {
                    if (throwable instanceof TimeoutException) {
                        player.sendMessage(TranslatableCaption.of("players.fetching_players_timeout"));
                    } else if (uuid != null && !PlotQuery.newQuery().ownedBy(uuid).anyMatch()) {
                        // Spieler gefunden, aber hat keine Plots
                        player.sendMessage(TranslatableCaption.of("errors.player_no_plots"));
                    } else if (uuid == null) {
                        // Kein Spieler gefunden -> als Alias behandeln
                        home(
                                player,
                                PlotQuery.newQuery().withAlias(arg0),
                                1,
                                confirm,
                                whenDone
                        );
                    } else {
                        // Spieler gefunden -> dessen Plots
                        PlotQuery query = PlotQuery.newQuery().ownedBy(uuid).whereBasePlot();
                        sortBySettings(query, player);
                        home(player, query, 1, confirm, whenDone);
                    }
                });
            }
            case 2 -> {
                final String arg0 = args[0];
                final String arg1 = args[1];

                // /p h <spieler> <zahl>  -> Plots des Spielers, Seite X
                if (!isInvalidPageNr(arg1)) {
                    int page = getPageNr(arg1);
                    if (page == Integer.MIN_VALUE) {
                        sendInvalidPageNrMsg(player);
                        return CompletableFuture.completedFuture(false);
                    }

                    // Erst prüfen ob arg0 eine Area ist
                    PlotArea plotArea = this.plotAreaManager.getPlotAreaByString(arg0);
                    if (plotArea != null) {
                        PlotQuery query = ownQuery(player).inArea(plotArea);
                        query.withSortingStrategy(SortingStrategy.SORT_BY_CREATION);
                        home(player, query, page, confirm, whenDone);
                        break;
                    }

                    // Sonst als Spielername interpretieren
                    final int finalPage = page;
                    PlotSquared.get().getImpromptuUUIDPipeline().getSingle(arg0, (uuid, throwable) -> {
                        if (throwable instanceof TimeoutException) {
                            player.sendMessage(TranslatableCaption.of("players.fetching_players_timeout"));
                        } else if (uuid == null) {
                            player.sendMessage(
                                    TranslatableCaption.of("errors.invalid_player"),
                                    TagResolver.resolver("value", Tag.inserting(Component.text(arg0)))
                            );
                        } else {
                            PlotQuery query = PlotQuery.newQuery().ownedBy(uuid).whereBasePlot();
                            sortBySettings(query, player);
                            home(player, query, finalPage, confirm, whenDone);
                        }
                    });
                    break;
                }

                // /p h <area> <x;y>  -> bestimmtes Plot in Area
                PlotArea plotArea = this.plotAreaManager.getPlotAreaByString(arg0);
                if (plotArea == null) {
                    sendUsage(player);
                    return CompletableFuture.completedFuture(false);
                }
                PlotId id = PlotId.fromStringOrNull(arg1);
                if (id == null) {
                    query(player).noPlots();
                    sendUsage(player);
                    return CompletableFuture.completedFuture(false);
                }
                Plot plot = plotArea.getPlot(id);
                if (plot == null || !plot.isOwner(player.getUUID())) {
                    player.sendMessage(TranslatableCaption.of("invalid.found_no_plots"));
                    return CompletableFuture.completedFuture(false);
                }
                home(player, PlotQuery.newQuery().withPlot(plot), 1, confirm, whenDone);
            }
            default -> sendUsage(player);
        }

        return CompletableFuture.completedFuture(true);
    }

    /**
     * Query für die eigenen Plots des ausführenden Spielers.
     */
    @NonNull
    private PlotQuery ownQuery(final @NonNull PlotPlayer<?> player) {
        return PlotQuery.newQuery().thatPasses(plot -> plot.isOwner(player.getUUID()));
    }

    /**
     * @deprecated Kept for internal compatibility.
     */
    @NonNull
    private PlotQuery query(final @NonNull PlotPlayer<?> player) {
        return ownQuery(player);
    }

    private boolean isInvalidPageNr(String arg) {
        return !MathMan.isInteger(arg) && !arg.equals("last") && !arg.equals("n");
    }

    private int getPageNr(String arg) {
        if (MathMan.isInteger(arg)) {
            try {
                return Integer.parseInt(arg);
            } catch (NumberFormatException ignored) {
                return Integer.MIN_VALUE;
            }
        } else if (arg.equals("last") || arg.equals("n")) {
            return -1;
        }
        return Integer.MIN_VALUE;
    }

    private void sendInvalidPageNrMsg(PlotPlayer<?> player) {
        player.sendMessage(
                TranslatableCaption.of("invalid.not_valid_number"),
                TagResolver.resolver("value", Tag.inserting(Component.text("(1, \u221e)")))
        );
        player.sendMessage(
                TranslatableCaption.of("commandconfig.command_syntax"),
                TagResolver.resolver("value", Tag.inserting(Component.text(getUsage())))
        );
    }

    private void sortBySettings(PlotQuery plotQuery, PlotPlayer<?> player) {
        PlotArea area = player.getApplicablePlotArea();
        if (Settings.Teleport.PER_WORLD_VISIT && area != null) {
            plotQuery.relativeToArea(area)
                    .withSortingStrategy(SortingStrategy.SORT_BY_CREATION);
        } else {
            plotQuery.withSortingStrategy(SortingStrategy.SORT_BY_TEMP);
        }
    }

    @Override
    public Collection<Command> tab(PlotPlayer<?> player, String[] args, boolean space) {
        final List<Command> completions = new ArrayList<>();
        switch (args.length - 1) {
            case 0 -> {
                completions.addAll(TabCompletions.completePlayers(player, args[0], Collections.emptyList()));
                completions.addAll(TabCompletions.completeAreas(args[0]));
                completions.addAll(TabCompletions.asCompletions("last"));
                if (args[0].isEmpty()) {
                    completions.addAll(TabCompletions.asCompletions("1", "2", "3"));
                    break;
                }
                completions.addAll(TabCompletions.completeNumbers(args[0], 10, 999));
            }
            case 1 -> {
                completions.addAll(TabCompletions.asCompletions("last"));
                completions.addAll(TabCompletions.completeNumbers(args[1], 10, 999));
            }
        }
        return completions;
    }

}
