package net.i2p.i2ptunnel.access;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.StatefulConnectionFilter;

/**
 * Factory for incoming connection filters. Only public class in this package.
 *
 * @since 0.9.40
 */
public class FilterFactory {

    /**
     * Creates an instance of IncomingConnectionFilter based on the definition
     * contained in the given file.
     *
     * @param context the context
     * @param definition file containing the filter definition
     */
    public static StatefulConnectionFilter createFilter(I2PAppContext context,
                                                        File definition)
        throws IOException, InvalidDefinitionException {
        List<String> linesList = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(definition), StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;
                if (line.startsWith("#"))
                    continue;
                linesList.add(line);
            }
        }

        FilterDefinition parsedDefinition = DefinitionParser.parse(linesList.toArray(new String[0]));
        return new AccessFilter(context, parsedDefinition);
    }
}
