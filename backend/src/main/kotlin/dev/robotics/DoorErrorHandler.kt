package dev.robotics

import io.grpc.Status
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [DoorController::class])
class DoorErrorHandler {
    @ExceptionHandler(DoorRpcException::class)
    fun grpcFailure(error: DoorRpcException): ResponseEntity<ApiError> {
        val (status, body) = when (error.failure.status.code) {
            Status.Code.INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST to
                ApiError("DOOR_INVALID_ARGUMENT", "Kapı simülasyonu için geçerli bir durum belirtin.")
            Status.Code.FAILED_PRECONDITION -> HttpStatus.CONFLICT to
                ApiError("DOOR_INVALID_STATE", "Kapı bu işleme hazır değil. Güncel durumu kontrol edip beklenen adımı tamamlayın.")
            Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE to
                ApiError("DOOR_UNAVAILABLE", "Kapı simülatörüne ulaşılamıyor.")
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT to
                ApiError("DOOR_TIMEOUT", "Kapı simülatörü zamanında yanıt vermedi.")
            else -> HttpStatus.BAD_GATEWAY to
                ApiError("DOOR_ERROR", "Kapı simülatörü isteği tamamlayamadı.")
        }
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(DoorRequestException::class, HttpMessageNotReadableException::class)
    fun invalidRequest(): ResponseEntity<ApiError> = ResponseEntity.badRequest().body(
        ApiError("DOOR_INVALID_REQUEST", "İstek gövdesinde expectedState dolu bir metin olmalı."),
    )
}
