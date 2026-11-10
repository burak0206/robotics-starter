package dev.robotics

import io.grpc.Status
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [FireAlarmController::class])
class FireAlarmErrorHandler {
    @ExceptionHandler(FireAlarmRpcException::class)
    fun grpcFailure(error: FireAlarmRpcException): ResponseEntity<ApiError> {
        val (status, body) = when (error.failure.status.code) {
            Status.Code.INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST to
                ApiError("FIRE_ALARM_INVALID_ARGUMENT", "Yangın alarmı simülasyon isteği geçersiz.")
            Status.Code.FAILED_PRECONDITION -> HttpStatus.CONFLICT to
                ApiError("FIRE_ALARM_INVALID_STATE", "Alarm etkinken iptal edilen senaryolar sıfırlanamaz. Önce alarmı temizleyin.")
            Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE to
                ApiError("FIRE_ALARM_UNAVAILABLE", "Yangın alarmı simülatörüne ulaşılamıyor.")
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT to
                ApiError("FIRE_ALARM_TIMEOUT", "Yangın alarmı simülatörü zamanında yanıt vermedi.")
            else -> HttpStatus.BAD_GATEWAY to
                ApiError("FIRE_ALARM_ERROR", "Yangın alarmı simülatörü isteği tamamlayamadı.")
        }
        return ResponseEntity.status(status).body(body)
    }
}
