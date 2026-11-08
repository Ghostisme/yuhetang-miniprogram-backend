package com.yuwatang.reservation;

import java.util.*;
import org.springframework.stereotype.Component;

/** Future adapters must verify payment/ledger receipts before any PAID or settlement transition exists. */
interface PaymentPort {
    record Request(String paymentId,String appointmentId,String payerIdentity,long amountFen,String currency,
                   String channel,String memberProfileId,String memberStoreId,String serviceStoreId,String idempotencyKey){}
    Map<String,Object> initiate(Request request);
    Map<String,Object> capabilities();
}

@Component
class ReservedPaymentPort implements PaymentPort {
    public Map<String,Object> capabilities(){return Map.of("mode","RESERVATION_ONLY","paymentEnabled",false,
        "channels",List.of(Map.of("code","MEMBER_BALANCE","enabled",false),Map.of("code","WECHAT_PAY","enabled",false)),
        "crossStoreSettlementEnabled",false);}
    public Map<String,Object> initiate(Request request){throw Data.error(501,"支付通道尚未启用，当前仅登记结算意向，不会发起微信支付或扣减会员储值。");}
}
