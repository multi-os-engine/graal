;
; This file was generated by LLVM/Clang.
;
; ModuleID = 'vectorKinds.ll'
target datalayout = "e-m:e-i64:64-f80:128-n8:16:32:64-S128"
target triple = "x86_64-unknown-linux-gnu"

$_Z9doReceiveIDv9_cEvT_ = comdat any

$_Z9doReceiveIDv8_sEvT_ = comdat any

$_Z9doReceiveIDv7_iEvT_ = comdat any

$_Z9doReceiveIDv6_lEvT_ = comdat any

$_Z9doReceiveIDv5_fEvT_ = comdat any

$_Z9doReceiveIDv4_dEvT_ = comdat any

$_Z3fooIDv9_cEvT_ = comdat any

$_Z3fooIDv8_sEvT_ = comdat any

$_Z3fooIDv7_iEvT_ = comdat any

$_Z3fooIDv6_lEvT_ = comdat any

$_Z3fooIDv5_fEvT_ = comdat any

$_Z3fooIDv4_dEvT_ = comdat any

@llvm.global_ctors = appending global [1 x { i32, void ()*, i8* }] [{ i32, void ()*, i8* } { i32 65535, void ()* @_Z4testv, i8* null }]

; Function Attrs: uwtable
define void @_Z4testv() #0 {
  %1 = alloca <7 x i32>, align 32
  %2 = alloca <6 x i64>, align 64
  %3 = alloca <5 x float>, align 32
  %4 = alloca <4 x double>, align 32
  call void @_Z9doReceiveIDv9_cEvT_(<9 x i8> <i8 48, i8 49, i8 50, i8 51, i8 52, i8 53, i8 54, i8 55, i8 56>)
  call void @_Z9doReceiveIDv8_sEvT_(<8 x i16> <i16 0, i16 1, i16 2, i16 3, i16 4, i16 5, i16 6, i16 7>)
  store <7 x i32> <i32 0, i32 1, i32 2, i32 3, i32 4, i32 5, i32 6>, <7 x i32>* %1, align 32
  call void @_Z9doReceiveIDv7_iEvT_(<7 x i32>* byval(<7 x i32>) align 32 %1)
  store <6 x i64> <i64 0, i64 1, i64 2, i64 3, i64 4, i64 5>, <6 x i64>* %2, align 64
  call void @_Z9doReceiveIDv6_lEvT_(<6 x i64>* byval(<6 x i64>) align 64 %2)
  store <5 x float> <float 0.000000e+00, float 0x3FF19999A0000000, float 0x40019999A0000000, float 0x400A666660000000, float 0x40119999A0000000>, <5 x float>* %3, align 32
  call void @_Z9doReceiveIDv5_fEvT_(<5 x float>* byval(<5 x float>) align 32 %3)
  store <4 x double> <double 0.000000e+00, double 1.100000e+00, double 2.200000e+00, double 3.300000e+00>, <4 x double>* %4, align 32
  call void @_Z9doReceiveIDv4_dEvT_(<4 x double>* byval(<4 x double>) align 32 %4)
  ret void
}

; Function Attrs: uwtable
define linkonce_odr void @_Z9doReceiveIDv9_cEvT_(<9 x i8> %toInspect) #0 comdat {
  call void @_Z3fooIDv9_cEvT_(<9 x i8> %toInspect)
  ret void
}

; Function Attrs: uwtable
define linkonce_odr void @_Z9doReceiveIDv8_sEvT_(<8 x i16> %toInspect) #0 comdat {
  call void @_Z3fooIDv8_sEvT_(<8 x i16> %toInspect)
  ret void
}

; Function Attrs: uwtable
define linkonce_odr void @_Z9doReceiveIDv7_iEvT_(<7 x i32>* byval(<7 x i32>) align 32) #0 comdat {
  %2 = alloca <7 x i32>, align 32
  %toInspect = load <7 x i32>, <7 x i32>* %0, align 32
  store <7 x i32> %toInspect, <7 x i32>* %2, align 32
  call void @_Z3fooIDv7_iEvT_(<7 x i32>* byval(<7 x i32>) align 32 %2)
  ret void
}

; Function Attrs: uwtable
define linkonce_odr void @_Z9doReceiveIDv6_lEvT_(<6 x i64>* byval(<6 x i64>) align 64) #0 comdat {
  %2 = alloca <6 x i64>, align 64
  %toInspect = load <6 x i64>, <6 x i64>* %0, align 64
  store <6 x i64> %toInspect, <6 x i64>* %2, align 64
  call void @_Z3fooIDv6_lEvT_(<6 x i64>* byval(<6 x i64>) align 64 %2)
  ret void
}

; Function Attrs: uwtable
define linkonce_odr void @_Z9doReceiveIDv5_fEvT_(<5 x float>* byval(<5 x float>) align 32) #0 comdat {
  %2 = alloca <5 x float>, align 32
  %toInspect = load <5 x float>, <5 x float>* %0, align 32
  store <5 x float> %toInspect, <5 x float>* %2, align 32
  call void @_Z3fooIDv5_fEvT_(<5 x float>* byval(<5 x float>) align 32 %2)
  ret void
}

; Function Attrs: uwtable
define linkonce_odr void @_Z9doReceiveIDv4_dEvT_(<4 x double>* byval(<4 x double>) align 32) #0 comdat {
  %2 = alloca <4 x double>, align 32
  %toInspect = load <4 x double>, <4 x double>* %0, align 32
  store <4 x double> %toInspect, <4 x double>* %2, align 32
  call void @_Z3fooIDv4_dEvT_(<4 x double>* byval(<4 x double>) align 32 %2)
  ret void
}

; Function Attrs: nounwind uwtable
define linkonce_odr void @_Z3fooIDv9_cEvT_(<9 x i8> %bar) #1 comdat {
  ret void
}

; Function Attrs: nounwind uwtable
define linkonce_odr void @_Z3fooIDv8_sEvT_(<8 x i16> %bar) #1 comdat {
  ret void
}

; Function Attrs: nounwind uwtable
define linkonce_odr void @_Z3fooIDv7_iEvT_(<7 x i32>* byval(<7 x i32>) align 32) #1 comdat {
  %bar = load <7 x i32>, <7 x i32>* %0, align 32
  ret void
}

; Function Attrs: nounwind uwtable
define linkonce_odr void @_Z3fooIDv6_lEvT_(<6 x i64>* byval(<6 x i64>) align 64) #1 comdat {
  %bar = load <6 x i64>, <6 x i64>* %0, align 64
  ret void
}

; Function Attrs: nounwind uwtable
define linkonce_odr void @_Z3fooIDv5_fEvT_(<5 x float>* byval(<5 x float>) align 32) #1 comdat {
  %bar = load <5 x float>, <5 x float>* %0, align 32
  ret void
}

; Function Attrs: nounwind uwtable
define linkonce_odr void @_Z3fooIDv4_dEvT_(<4 x double>* byval(<4 x double>) align 32) #1 comdat {
  %bar = load <4 x double>, <4 x double>* %0, align 32
  ret void
}

attributes #0 = { uwtable "disable-tail-calls"="false" "less-precise-fpmad"="false" "no-frame-pointer-elim"="true" "no-frame-pointer-elim-non-leaf" "no-infs-fp-math"="false" "no-nans-fp-math"="false" "stack-protector-buffer-size"="8" "target-cpu"="x86-64" "target-features"="+fxsr,+mmx,+sse,+sse2" "unsafe-fp-math"="false" "use-soft-float"="false" }
attributes #1 = { nounwind uwtable "disable-tail-calls"="false" "less-precise-fpmad"="false" "no-frame-pointer-elim"="true" "no-frame-pointer-elim-non-leaf" "no-infs-fp-math"="false" "no-nans-fp-math"="false" "stack-protector-buffer-size"="8" "target-cpu"="x86-64" "target-features"="+fxsr,+mmx,+sse,+sse2" "unsafe-fp-math"="false" "use-soft-float"="false" }

!llvm.ident = !{!0}

!0 = !{!"clang version 3.8.1 (tags/RELEASE_381/final)"}
