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
 * {@code nonhost_load}/{@code nonhost_unload} 의 target 인자.
 *
 * <p>{@code StringArgumentType.word()} 는 공백에서 끊겨 {@code @a[limit=2, sort=nearest]} 가 두 토막이 됐다.
 * 이 타입은 {@code @} 로 시작하면 대괄호 짝이 닫힐 때까지(따옴표 안 포함) 통째로 읽고, 아니면 한 단어를 읽는다.
 * 클라 전용 명령이라 인자 타입 레지스트리 등록이 필요 없다 (서버로 직렬화되지 않는다).
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
