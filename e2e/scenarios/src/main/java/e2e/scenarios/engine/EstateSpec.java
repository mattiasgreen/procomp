package e2e.scenarios.engine;

/**
 * Declarative estate topology specification bootstrapped on {@link ScenarioWorld#reset(EstateSpec)}.
 */
public sealed interface EstateSpec permits EstateSpec.StandardTopology, EstateSpec.LargeEstate {

    /**
     * Standard 4-service topology (broker, orders, pricing, notifications) with WireMock Gateway.
     */
    record StandardTopology() implements EstateSpec {
        public static final StandardTopology INSTANCE = new StandardTopology();
    }

    /**
     * Parameterized N-service estate for InMemoryWorld scaling and UX testing.
     */
    record LargeEstate(int serviceCount) implements EstateSpec {
        public LargeEstate {
            if (serviceCount < 1) {
                throw new IllegalArgumentException("serviceCount must be positive: " + serviceCount);
            }
        }
    }
}
