package fr.pierrezemb.recordstore;


import fr.pierrezemb.recordstore.fdb.RecordLayer;
import fr.pierrezemb.recordstore.grpc.AdminService;
import fr.pierrezemb.recordstore.grpc.AuthInterceptor;
import fr.pierrezemb.recordstore.grpc.RecordService;
import fr.pierrezemb.recordstore.grpc.SchemaService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.grpc.VertxServer;
import io.vertx.grpc.VertxServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static fr.pierrezemb.recordstore.auth.BiscuitManager.DEFAULT_BISCUIT_KEY;

public class GrpcVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcVerticle.class);

  @Override
  public void start(Promise<Void> startPromise) throws Exception {

    String clusterFilePath = this.context.config().getString("fdb-cluster-file", "/var/fdb/fdb.cluster");
    System.out.println("connecting to fdb@" + clusterFilePath);

    String key = this.context.config().getString("biscuit-key", DEFAULT_BISCUIT_KEY);
    if (key.equals(DEFAULT_BISCUIT_KEY)) {
      LOGGER.warn("using default key for tokens");
    }

    RecordLayer recordLayer = new RecordLayer(clusterFilePath, vertx.isMetricsEnabled());

    VertxServerBuilder serverBuilder = VertxServerBuilder
      .forAddress(vertx,
        this.context.config().getString("listen-address", "localhost"),
        this.context.config().getInteger("grpc-listen-port", 8080))
      .intercept(new AuthInterceptor(key))
      .addService(new AdminService(recordLayer))
      .addService(new SchemaService(recordLayer))
      .addService(new RecordService(recordLayer));

    VertxServer server = serverBuilder.build();

    server.start(ar -> {
      if (ar.succeeded()) {
        System.out.println("gRPC service started on " + this.context.config().getInteger("grpc-listen-port"));
        startPromise.complete();
      } else {
        System.out.println("Could not start server " + ar.cause().getMessage());
        startPromise.fail(ar.cause());
      }
    });
  }
}
