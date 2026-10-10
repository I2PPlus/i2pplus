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
     * Constructor. Filters are always built by the static createFilter() from a
     * definition file, so an instance carries no state.
     */
    public FilterFactory() {}

    /**
     * Creates an instance of IncomingConnectionFilter based on the definition
     * contained in the given file.
     *
     * @param context the app context whose service references the created filter consults
     * @param definition file containing the filter definition
     * @return the filter built from the parsed definition, ready to filter connections
     * @throws IOException if the definition file cannot be read
     * @throws InvalidDefinitionException if the file does not parse as a filter definition
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
