alter table payments add column razorpay_receipt varchar(40);

alter table payments add constraint uk_payments_razorpay_receipt
    unique (razorpay_receipt);