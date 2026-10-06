package com.flowify.ettea.lyrics.reading;

import com.flowify.ettea.lyrics.reading.ReadingContracts.CanonicalLineBuilder;
import com.flowify.ettea.lyrics.reading.ReadingModels.CanonicalLine;
import com.flowify.ettea.lyrics.reading.ReadingModels.ParsedLine;

public final class DefaultCanonicalLineBuilder implements CanonicalLineBuilder {
    private final ProviderBoundaryResolver resolver = new ProviderBoundaryResolver();

    @Override
    public CanonicalLine build(ParsedLine line) {
        return resolver.resolve(line).canonical;
    }
}
