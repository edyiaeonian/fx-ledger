package dev.edyiaeonian.fxledger.fx;

/**
 * Where reference rates come from. The application uses the Frankfurter API;
 * tests can supply any rates they like.
 */
public interface RateSource {

    /**
     * The latest published rates.
     *
     * @throws RateSourceException if they cannot be fetched or read
     */
    RateSnapshot fetch();
}
