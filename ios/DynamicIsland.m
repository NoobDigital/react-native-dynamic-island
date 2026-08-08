#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(DynamicIsland, NSObject)

RCT_EXTERN_METHOD(
  areActivitiesEnabled:(RCTPromiseResolveBlock)resolve
  rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(
  startActivity:(NSDictionary *)content
  attributes:(NSDictionary *)attributes
  resolver:(RCTPromiseResolveBlock)resolve
  rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(
  updateActivity:(NSDictionary *)content
  resolver:(RCTPromiseResolveBlock)resolve
  rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(
  endActivity:(RCTPromiseResolveBlock)resolve
  rejecter:(RCTPromiseRejectBlock)reject
)

@end