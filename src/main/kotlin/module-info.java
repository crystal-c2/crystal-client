module com.crystalc2.client {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;
    requires io.grpc;
    requires io.grpc.stub;
    requires io.grpc.protobuf;
    requires io.grpc.netty;
    requires com.google.protobuf;
    requires java.annotation;
    requires com.google.common;
    requires sleep;

    opens com.crystalc2.client to javafx.fxml;
    exports com.crystalc2.client;
}