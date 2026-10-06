package com.dautolock.app.api;

/**
 * 비동기 API 호출 결과를 처리하기 위한 콜백 인터페이스입니다. Android 환경에서 네트워크 호출은 메인 스레드(UI 스레드)에서 실행될 수 없으므로, OkHttp의
 * enqueue와 함께 사용하여 결과를 비동기로 전달받을 수 있도록 설계되었습니다.
 *
 * @param <T> 성공 시 반환될 데이터 타입 (예: VehicleStatus)
 */
public interface BydApiCallback<T> {

  /**
   * API 호출이 성공적으로 완료되었을 때 호출됩니다. Android 환경에서는 이 콜백 안에서 UI를 업데이트하려면 runOnUiThread 또는
   * Handler(Looper.getMainLooper())를 사용해야 합니다.
   *
   * @param result 반환된 데이터
   */
  void onSuccess(T result);

  /**
   * 네트워크 오류, JSON 파싱 오류 또는 API 실패 응답 시 호출됩니다.
   *
   * @param errorMessage 오류에 대한 설명 메시지
   * @param e 발생한 예외 객체 (없을 경우 null)
   */
  void onError(String errorMessage, Exception e);
}
