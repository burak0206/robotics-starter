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
@RestControllerAdvice(assignableTypes = [ElevatorController::class])
class ElevatorErrorHandler {
    @ExceptionHandler(ElevatorRpcException::class)
    fun grpcFailure(error: ElevatorRpcException): ResponseEntity<ApiError> {
        val (status, body) = when (error.failure.status.code) {
            Status.Code.INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST to
                ApiError("ELEVATOR_INVALID_ARGUMENT", "Kat veya simülasyon olayı geçersiz. Katlar 0 ile 5 arasında, hedef kat çağrı katından farklı olmalı.")
            Status.Code.FAILED_PRECONDITION -> HttpStatus.CONFLICT to
                ApiError("ELEVATOR_INVALID_STATE", "Asansör bu işleme hazır değil. Güncel durumu kontrol edip beklenen adımı tamamlayın.")
            Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE to
                ApiError("ELEVATOR_UNAVAILABLE", "Asansör simülatörüne ulaşılamıyor.")
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT to
                ApiError("ELEVATOR_TIMEOUT", "Asansör simülatörü zamanında yanıt vermedi.")
            else -> HttpStatus.BAD_GATEWAY to
                ApiError("ELEVATOR_ERROR", "Asansör simülatörü isteği tamamlayamadı.")
        }
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(ElevatorRequestException::class, HttpMessageNotReadableException::class)
    fun invalidRequest(): ResponseEntity<ApiError> = ResponseEntity.badRequest().body(
        ApiError("ELEVATOR_INVALID_REQUEST", "İstek gövdesini kontrol edin: kat bir tamsayı, expectedState dolu bir metin olmalı."),
    )
}
