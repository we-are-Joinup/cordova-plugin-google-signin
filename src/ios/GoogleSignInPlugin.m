/********* GoogleSignInPlugin.m Cordova Plugin Implementation *******/
// Updated for GoogleSignIn SDK 7.x compatibility

#import <Cordova/CDV.h>

#import <GoogleSignIn/GoogleSignIn.h>

@interface GoogleSignInPlugin : CDVPlugin {
  // Member variables go here.
}

@property (nonatomic, assign) BOOL isSigningIn;
@property (nonatomic, copy) NSString* callbackId;
@end

@implementation GoogleSignInPlugin

- (void)pluginInitialize
{
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(handleOpenURL:) name:CDVPluginHandleOpenURLNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(handleOpenURLWithAppSourceAndAnnotation:) name:CDVPluginHandleOpenURLWithAppSourceAndAnnotationNotification object:nil];
}

//============

- (void)handleOpenURL:(NSNotification*)notification
{
    // no need to handle this handler, we dont have an sourceApplication here, which is required by GIDSignIn handleURL
}

- (void)handleOpenURLWithAppSourceAndAnnotation:(NSNotification*)notification
{
    NSMutableDictionary * options = [notification object];

    NSURL* url = options[@"url"];

    NSString* possibleReversedClientId = [url.absoluteString componentsSeparatedByString:@":"].firstObject;

    if ([possibleReversedClientId isEqualToString:self.getreversedClientId] && self.isSigningIn) {
        self.isSigningIn = NO;
        [GIDSignIn.sharedInstance handleURL:url];
    }
}

- (void) signIn:(CDVInvokedUrlCommand*)command {
    _callbackId = command.callbackId;
    NSString *reversedClientId = [self getreversedClientId];

    if (reversedClientId == nil) {
        NSDictionary *errorDetails = @{@"status": @"error", @"message": @"Could not find REVERSED_CLIENT_ID url scheme in app .plist"};
        CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[self toJSONString:errorDetails]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:_callbackId];
        return;
    }

    NSString *clientId = [self reverseUrlScheme:reversedClientId];

    // SDK 7.x: Configure the shared instance
    GIDConfiguration *config = [[GIDConfiguration alloc] initWithClientID:clientId];
    GIDSignIn.sharedInstance.configuration = config;
    
    self.isSigningIn = YES;
    
    // SDK 7.x: Use signInWithPresentingViewController:completion: instead of signInWithConfiguration:presentingViewController:callback:
    [GIDSignIn.sharedInstance signInWithPresentingViewController:self.viewController completion:^(GIDSignInResult * _Nullable result, NSError * _Nullable error) {
        self.isSigningIn = NO;
        
        if (error) {
            NSDictionary *errorDetails = @{@"status": @"error", @"message": error.localizedDescription};
            CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[self toJSONString:errorDetails]];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self->_callbackId];
        } else {
            // SDK 7.x: Get user from result
            GIDGoogleUser *user = result.user;
            
            NSString *email = user.profile.email;
            NSString *userId = user.userID;
            NSURL *imageUrl = [user.profile imageURLWithDimension:120];
            
            // SDK 7.x: idToken is now directly on user, and we need .tokenString
            NSString *idToken = user.idToken.tokenString;
            NSString *accessToken = user.accessToken.tokenString;
            NSString *refreshToken = user.refreshToken.tokenString;
            
            NSDictionary *resultDict = @{
                           @"email"            : email ?: [NSNull null],
                           @"id"               : userId ?: [NSNull null],
                           @"id_token"         : idToken ?: [NSNull null],
                           @"access_token"     : accessToken ?: [NSNull null],
                           @"refresh_token"    : refreshToken ?: [NSNull null],
                           @"display_name"     : user.profile.name ?: [NSNull null],
                           @"given_name"       : user.profile.givenName ?: [NSNull null],
                           @"family_name"      : user.profile.familyName ?: [NSNull null],
                           @"photo_url"        : imageUrl ? imageUrl.absoluteString : [NSNull null],
                           };


            NSDictionary *response = @{@"message": resultDict, @"status": @"success"};
            
            CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString: [self toJSONString:response]];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self->_callbackId];
        }
    }];
}

- (NSString*) reverseUrlScheme:(NSString*)scheme {
    NSArray* originalArray = [scheme componentsSeparatedByString:@"."];
    NSArray* reversedArray = [[originalArray reverseObjectEnumerator] allObjects];
    NSString* reversedString = [reversedArray componentsJoinedByString:@"."];
    return reversedString;
}

- (NSString*) getreversedClientId {
    NSArray* URLTypes = [[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleURLTypes"];

    if (URLTypes != nil) {
        for (NSDictionary* dict in URLTypes) {
            NSString *urlName = dict[@"CFBundleURLName"];
            if ([urlName isEqualToString:@"REVERSED_CLIENT_ID"]) {
                NSArray* URLSchemes = dict[@"CFBundleURLSchemes"];
                if (URLSchemes != nil) {
                    return URLSchemes[0];
                }
            }
        }
    }
    return nil;
}

- (void) signOut:(CDVInvokedUrlCommand*)command {
    [GIDSignIn.sharedInstance signOut];
    NSDictionary *details = @{@"status": @"success", @"message": @"Logged out"};
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:[self toJSONString:details]];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void) disconnect:(CDVInvokedUrlCommand*)command {
    // SDK 7.x: Use disconnectWithCompletion: instead of disconnectWithCallback:
    [GIDSignIn.sharedInstance disconnectWithCompletion:^(NSError * _Nullable error) {
        if(error == nil) {
            NSDictionary *details = @{@"status": @"success", @"message": @"Disconnected"};
            CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:[self toJSONString:details]];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        } else {
            NSDictionary *details = @{@"status": @"error", @"message": [error localizedDescription]};
            CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[self toJSONString:details]];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        }
    }];
}

- (void) isSignedIn:(CDVInvokedUrlCommand*)command {
    bool isSignedIn = [GIDSignIn.sharedInstance currentUser] != nil;
    NSDictionary *details = @{@"status": @"success", @"message": (isSignedIn) ? @"true" : @"false"};
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:[self toJSONString:details]];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (NSString*)toJSONString:(NSDictionary*)dictionaryOrArray {
    NSError *error;
         NSData *jsonData = [NSJSONSerialization dataWithJSONObject:dictionaryOrArray
                                                       options:NSJSONWritingPrettyPrinted
                                                         error:&error];
         if (! jsonData) {
            NSLog(@"%s: error: %@", __func__, error.localizedDescription);
            return @"{}";
         } else {
            return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
         }
}

@end
