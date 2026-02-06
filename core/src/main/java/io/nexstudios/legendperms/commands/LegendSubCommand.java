package io.nexstudios.legendperms.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

public interface LegendSubCommand {
    LiteralArgumentBuilder<CommandSourceStack> build();
}