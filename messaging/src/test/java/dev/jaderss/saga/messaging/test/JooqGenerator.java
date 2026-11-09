package dev.jaderss.saga.messaging.test;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.ForcedType;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class JooqGenerator {

    private static final String TARGET_DIRECTORY = "src/generated/java";

    private JooqGenerator() {}

    public static ForcedType enumColumns(Class<? extends Enum<?>> type, String columns) {
        return new ForcedType()
                .withUserType(type.getName())
                .withEnumConverter(true)
                .withIncludeExpression(columns);
    }

    public static void generate(String packageName, List<String> flywayLocations, ForcedType... forcedTypes)
            throws Exception {
        try (var postgres = new PostgreSQLContainer(SagaContainers.POSTGRES_IMAGE)) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations(flywayLocations.toArray(String[]::new))
                    .load()
                    .migrate();
            GenerationTool.generate(new Configuration()
                    .withJdbc(new Jdbc()
                            .withUrl(postgres.getJdbcUrl())
                            .withUser(postgres.getUsername())
                            .withPassword(postgres.getPassword()))
                    .withGenerator(new Generator()
                            .withDatabase(new Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema("public")
                                    .withExcludes("flyway_schema_history")
                                    .withForcedTypes(forcedTypes))
                            .withGenerate(new Generate()
                                    .withJavadoc(false)
                                    .withComments(false)
                                    .withGeneratedAnnotation(false))
                            .withTarget(new Target()
                                    .withPackageName(packageName)
                                    .withDirectory(TARGET_DIRECTORY)
                                    .withClean(true))));
        }
    }
}
