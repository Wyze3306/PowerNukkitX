package org.powernukkitx.command.data;

import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An optional message parameter must never reach the client: it asks a greedy rest-of-line node to
 * decide whether to take the rest of the line, which the client parser walks on every keystroke.
 */
class OptionalMessageOverloadTest {

    private static CommandData toNetwork(String overload, CommandParameter... parameters) {
        NukkitCommandData data = new NukkitCommandData("msg");
        CommandOverload input = new CommandOverload();
        input.input.parameters = parameters;
        data.overloads.put(overload, input);
        return data.toNetwork();
    }

    @Test
    void optionalMessageIsSplitIntoTwoOverloads() {
        CommandData data = toNetwork("default",
                CommandParameter.newType("joueur", true, CommandParamType.SELECTION),
                CommandParameter.newType("message", true, CommandParamType.MESSAGE));

        assertEquals(2, data.getOverloads().length);

        CommandParamData[] shortForm = data.getOverloads()[0].getOverloads();
        assertEquals(1, shortForm.length);
        assertEquals("joueur", shortForm[0].getName());
        assertTrue(shortForm[0].isOptional(), "the bare command must stay typeable");

        CommandParamData[] longForm = data.getOverloads()[1].getOverloads();
        assertEquals(2, longForm.length);
        assertFalse(longForm[0].isOptional(), "an optional in front of a required one is the same trap");
        assertFalse(longForm[1].isOptional());
        assertEquals("message", longForm[1].getName());
    }

    @Test
    void requiredMessageIsLeftAlone() {
        CommandData data = toNetwork("default",
                CommandParameter.newType("texte", CommandParamType.MESSAGE));

        assertEquals(1, data.getOverloads().length);
        assertEquals(1, data.getOverloads()[0].getOverloads().length);
        assertFalse(data.getOverloads()[0].getOverloads()[0].isOptional());
    }

    @Test
    void otherOptionalParametersAreLeftAlone() {
        CommandData data = toNetwork("default",
                CommandParameter.newType("mode", CommandParamType.INT),
                CommandParameter.newType("joueur", true, CommandParamType.SELECTION));

        assertEquals(1, data.getOverloads().length);
        assertTrue(Arrays.stream(data.getOverloads()[0].getOverloads())
                .anyMatch(CommandParamData::isOptional), "only rest-of-line parameters are the problem");
    }

    @Test
    void messageInFirstPositionLeavesAnEmptyShortForm() {
        CommandData data = toNetwork("default",
                CommandParameter.newType("arg", true, CommandParamType.MESSAGE));

        assertEquals(2, data.getOverloads().length);
        assertEquals(0, data.getOverloads()[0].getOverloads().length);
        assertEquals(1, data.getOverloads()[1].getOverloads().length);
        assertFalse(data.getOverloads()[1].getOverloads()[0].isOptional());
    }
}
