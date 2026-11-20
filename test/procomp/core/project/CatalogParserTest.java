package procomp.core.project;

import procomp.Assert;
import procomp.core.common.DomainError;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CatalogParserTest {

    public static void testParsesNestedItemsPathWithStringPortsAndIgnoresUnmappedFields() {
        CatalogMapping mapping = new CatalogMapping(
            "./service-index.json",
            Optional.of("apps"),
            Map.of(
                "name", "servicename",
                "port", "localport",
                "runtime", "type",
                "repositoryUrl", "servicerepositoryurl",
                "vars.subproject", "subproject"
            )
        );
        String catalogJson = """
            {
              "apps": [
                {
                  "servicename": "service1",
                  "localport": "9001",
                  "subproject": "service1",
                  "type": "java",
                  "swaggeruri": "swagger-ui/index.html",
                  "servicerepositoryurl": "ssh://git@scm.corp:7999/acme/service1.git"
                },
                {
                  "servicename": "service2",
                  "localport": 9002,
                  "subproject": "service2",
                  "type": "java",
                  "extraIgnored": {"nested": true}
                }
              ]
            }
            """;

        Map<String, RawProjectConfig.RawServiceConfig> services = Assert.assertOk(
            CatalogParser.parse(mapping, catalogJson),
            "catalog should parse cleanly"
        );
        Assert.assertEquals(List.of("service1", "service2"), List.copyOf(services.keySet()), "service order preserved");

        RawProjectConfig.RawServiceConfig s1 = services.get("service1");
        Assert.assertEquals(Optional.of(9001), s1.port(), "string port coerced to integer");
        Assert.assertEquals(Optional.of("java"), s1.runtime(), "runtime mapped");
        Assert.assertEquals(Optional.of("ssh://git@scm.corp:7999/acme/service1.git"), s1.repositoryUrl(), "repo url mapped");
        Assert.assertEquals(new VarValue.Scalar("service1"), s1.vars().get("subproject"), "vars.subproject mapped");

        RawProjectConfig.RawServiceConfig s2 = services.get("service2");
        Assert.assertEquals(Optional.of(9002), s2.port(), "integer port accepted");
    }

    public static void testParsesRootArrayWhenItemsPathOmitted() {
        CatalogMapping mapping = new CatalogMapping(
            "root-array.json",
            Optional.empty(),
            Map.of("name", "id", "runtime", "rt", "vars.flags", "flags")
        );
        String json = """
            [
              { "id": "orders", "rt": "node", "flags": ["--inspect", "--trace"] }
            ]
            """;

        Map<String, RawProjectConfig.RawServiceConfig> services = Assert.assertOk(
            CatalogParser.parse(mapping, json),
            "root array catalog should parse"
        );
        Assert.assertEquals(
            new VarValue.ListValue(List.of("--inspect", "--trace")),
            services.get("orders").vars().get("flags"),
            "string[] var mapped from catalog"
        );
    }

    public static void testRejectsInvalidPortsDuplicateNamesAndMissingItemsPath() {
        CatalogMapping mapping = new CatalogMapping(
            "idx.json",
            Optional.of("data.apps"),
            Map.of("name", "n", "port", "p")
        );

        List<String> invalidPortCatalogs = List.of(
            "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":\"abc\"}]}}",
            "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":\"9001.5\"}]}}",
            "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":\" 9001 \"}]}}",
            "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":\"70000\"}]}}",
            "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":\"0\"}]}}",
            "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":9001.5}]}}",
            "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":true}]}}"
        );
        for (String bad : invalidPortCatalogs) {
            DomainError err = Assert.assertErr(CatalogParser.parse(mapping, bad), "should reject invalid catalog port: " + bad);
            Assert.assertTrue(err.code().startsWith("CATALOG_"), "CATALOG_* error for: " + bad);
        }

        String dupJson = "{\"data\":{\"apps\":[{\"n\":\"s1\",\"p\":9001},{\"n\":\"s1\",\"p\":9002}]}}";
        Assert.assertErr(CatalogParser.parse(mapping, dupJson), "duplicate catalog service name must fail");

        String missingPathJson = "{\"other\":[]}";
        Assert.assertErr(CatalogParser.parse(mapping, missingPathJson), "missing itemsPath must fail");
    }
}
