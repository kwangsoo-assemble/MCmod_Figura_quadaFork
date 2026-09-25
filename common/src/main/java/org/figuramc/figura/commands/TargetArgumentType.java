package org.figuramc.figura.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Collection;

/**
 * The target argument of {@code nonhost_load}/{@code nonhost_unload}.
 *
 * <p>{@code StringArgumentType.word()} stops at whitespace, which split {@code @a[limit=2, sort=nearest]} in two.
 * When the input starts with {@code @}, this type reads it whole until the brackets are balanced (quoted parts
 * included); otherwise it reads a single word.
 * These are client-only commands, so no argument type registry entry is needed (nothing is serialized to the server).
 */
public final class TargetArgumentType implements ArgumentType<String> {

    private static final SimpleCommandExceptionType UNCLOSED =
            new SimpleCommandExceptionType(Component.literal("unclosed '[' in selector"));

    public static TargetArgumentType target() {
        return new TargetArgumentType();
    }

    public static String getTarget(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        if (reader.canRead() && reader.peek() == '@') {
            reader.skip();
            while (reader.canRead() && reader.peek() != ' ' && reader.peek() != '[')
                reader.skip();
            if (reader.canRead() && reader.peek() == '[') {
                int depth = 0;
                char quote = 0;
                while (reader.canRead()) {
                    char c = reader.read();
                    if (quote != 0) {
                        if (c == '\\' && reader.canRead()) reader.skip();
                        else if (c == quote) quote = 0;
                    } else if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                if (depth != 0)
                    throw UNCLOSED.createWithContext(reader);
            }
            return reader.getString().substring(start, reader.getCursor());
        }
        return reader.readUnquotedString();
    }

    @Override
    public Collection<String> getExamples() {
        return Arrays.asList("Steve", "@a", "@e[type=player,distance=..10]", "@look");
    }
}
