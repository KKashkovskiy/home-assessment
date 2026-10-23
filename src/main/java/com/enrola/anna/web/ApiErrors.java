package com.enrola.anna.web;

import com.enrola.anna.conversation.BusyException;
import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.NotFoundException;
import java.util.Locale;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps the service's typed exceptions and rejected requests to status codes and {@link ApiError}.
 * Messages name fields as they appear on the wire and never repeat framework text or raw input.
 */
@RestControllerAdvice
class ApiErrors {

  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  ApiError notFound(NotFoundException e) {
    return new ApiError("NOT_FOUND", e.getMessage(), null);
  }

  @ExceptionHandler(ConflictException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  ApiError conflict(ConflictException e) {
    return new ApiError(e.code(), e.getMessage(), e.conversationId());
  }

  /** Only reachable when a writer bypassed the in-process lock; the platform retries. */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  ApiError lostUpdate() {
    return new ApiError(BusyException.CODE, "conversation was updated concurrently", null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError invalidBody(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(ApiErrors::describe)
            .orElse("request body is invalid");
    return validation(message);
  }

  /**
   * Raised instead of {@link MethodArgumentNotValidException} when the handler also has a
   * constrained parameter, such as a validated path id, so it can carry a body's field errors too.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError invalidParameter(HandlerMethodValidationException e) {
    String message =
        e.getParameterValidationResults().stream()
            .findFirst()
            .map(ApiErrors::describe)
            .orElse("request is invalid");
    return validation(message);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError wrongParameterType(MethodArgumentTypeMismatchException e) {
    return validation(wireName(e.getName()) + " has the wrong type");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError unreadable() {
    return validation("request body must be a JSON object");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
  ApiError unsupportedMediaType() {
    return new ApiError("UNSUPPORTED_MEDIA_TYPE", "content type must be application/json", null);
  }

  private static ApiError validation(String message) {
    return new ApiError("VALIDATION", message, null);
  }

  /** A validated body names its first invalid field; any other parameter names itself. */
  private static String describe(ParameterValidationResult result) {
    if (result instanceof ParameterErrors body && body.hasFieldErrors()) {
      return describe(body.getFieldErrors().getFirst());
    }
    return result.getMethodParameter().getParameterName()
        + " "
        + result.getResolvableErrors().getFirst().getDefaultMessage();
  }

  private static String describe(FieldError error) {
    return wireName(error.getField()) + " " + error.getDefaultMessage();
  }

  /** leadId to lead_id, matching the app's snake_case Jackson naming. */
  private static String wireName(String field) {
    return field.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
  }
}
