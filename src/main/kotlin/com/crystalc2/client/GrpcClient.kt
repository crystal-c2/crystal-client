package com.crystalc2.client

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor

object GrpcClient {
    private var _channel: ManagedChannel? = null

    val channel: ManagedChannel
        get() = _channel ?: error("GrpcClient not initialized — login first")

    fun init(host: String, port: Int, token: String) {
        _channel?.shutdown()
        _channel = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .intercept(BearerTokenInterceptor(token))
            .build()
    }

    fun shutdown() { _channel?.shutdown() }

    private class BearerTokenInterceptor(private val token: String) : ClientInterceptor {
        private val authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)

        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel
        ): ClientCall<ReqT, RespT> =
            object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)
            ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(authKey, "Bearer $token")
                    super.start(responseListener, headers)
                }
            }
    }
}