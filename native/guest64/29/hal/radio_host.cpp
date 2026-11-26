// aemu-radio: an android.hardware.radio@1.4::IRadio/slot1 service for GSI guests.
//
// A Google GSI has no vendor radio HAL: hwservicemanager finds no IRadio, the telephony
// framework reports "SIM absent" and the quick-settings tile says "No SIM card", even
// though the engine already has a working fake modem — RilStub on the host answers the
// legacy RIL parcel protocol on /dev/socket/rild, and on the 6.0 guest the phone process
// speaks that protocol itself. On Android 10 the framework no longer speaks it; it wants
// HIDL IRadio. This service is the missing half: a binderized IRadio whose answers are
// translated from the same RilStub, so the identity the guest shows (operator, ICCID,
// IMEI/IMSI, baseband) is exactly the one the 6.0 guest shows — it comes from the same
// settings through the same code. Data calls are answered with a failure result on
// purpose: the guest's data path is the engine's own and must not be rerouted through
// telephony. Nothing here registers to a real network, places calls or sends SMS.
//
// No AOSP headers: the C++ classes below mirror the generated HIDL headers only in
// inheritance shape and virtual-function order, which is what fixes the vtable layout
// the platform libraries dispatch through. Struct layouts are the HIDL wire layouts
// from radio/{1.0,1.2}/types.hal (checked with static_asserts). Entry points in the
// platform libraries are taken by their mangled names, as in the omxstore host.
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <errno.h>
#include <functional>
#include <mutex>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>
#include <utility>
#include <vector>

#define LOG(...) do { fprintf(stderr, "aemu-radio: " __VA_ARGS__); fputc('\n', stderr); fflush(stderr); } while (0)

// ---------------------------------------------------------------------------
// ABI mirrors: RefBase, Return, hidl types, IBase.
// ---------------------------------------------------------------------------
namespace android {

class RefBase {
protected:
    RefBase();
    virtual ~RefBase();
    virtual void onFirstRef();
    virtual void onLastStrongRef(const void* id);
    virtual bool onIncStrongAttempted(uint32_t flags, const void* id);
    virtual void onLastWeakRef(const void* id);
private:
    class weakref_impl;
    weakref_impl* const mRefs;   // the only data member, as in the real RefBase
};

template<typename T> struct sp { T* mPtr; };
template<typename T> struct wp { T* mPtr; void* mRefs; };

}  // namespace android

// Set in main() before the service object exists: the real RefBase constructor from
// libutils, so the weakref block behind mRefs is a genuine one and the platform's
// (non-virtual) incStrong/decStrong work on this object exactly as on a real one.
static void (*g_refbase_ctor)(void*) = nullptr;
static void (*g_incStrong)(const void* self, const void* id) = nullptr;
static void (*g_decStrong)(const void* self, const void* id) = nullptr;

namespace android {
RefBase::RefBase() : mRefs(nullptr) { if (g_refbase_ctor) g_refbase_ctor(this); }
RefBase::~RefBase() {}
void RefBase::onFirstRef() {}
void RefBase::onLastStrongRef(const void*) {}
bool RefBase::onIncStrongAttempted(uint32_t, const void*) { return true; }
void RefBase::onLastWeakRef(const void*) {}
}  // namespace android

namespace android { namespace hidl { namespace base { namespace V1_0 { struct IBase; } } } }

namespace android { namespace hardware {

// Return<T>: only the status word matters to callers; zero is OK in the real layout.
template<typename T> class Return;
template<> class Return<void> {
    uint64_t mStatus;
public:
    Return() : mStatus(0) {}
    ~Return() {}
};
template<> class Return<bool> {
    uint64_t mStatus;
    bool mValue;
public:
    Return(bool v) : mStatus(0), mValue(v) {}
    ~Return() {}
};

struct hidl_string {
    const char* buffer = nullptr;
    uint32_t length = 0;
    bool ownsBuffer = false;
private:
    char mPad[3] = {};
public:
    hidl_string() = default;
    hidl_string(const char* s) : buffer(s), length(s ? (uint32_t)strlen(s) : 0) {}
    hidl_string(const std::string& s) : buffer(s.c_str()), length((uint32_t)s.size()) {}
};
static_assert(sizeof(hidl_string) == 16, "hidl_string");

template<typename T> struct hidl_vec {
    const T* buffer = nullptr;
    uint32_t size = 0;
    bool ownsBuffer = false;
private:
    char mPad[3] = {};
public:
    hidl_vec() = default;
    hidl_vec(const T* b, uint32_t n) : buffer(b), size(n) {}
};
static_assert(sizeof(hidl_vec<int32_t>) == 16, "hidl_vec");

struct hidl_handle {
    const void* handle = nullptr;
    bool ownsHandle = false;
private:
    char mPad[7] = {};
};
static_assert(sizeof(hidl_handle) == 16, "hidl_handle");

template<typename T, size_t N> struct hidl_array { T mData[N]; };

struct hidl_death_recipient : virtual public ::android::RefBase {
    virtual void serviceDied(uint64_t cookie, const ::android::wp< ::android::hidl::base::V1_0::IBase>& who) = 0;
};

} }  // namespace android::hardware

namespace android { namespace hidl { namespace base { namespace V1_0 {

struct DebugInfo {
    int32_t pid;
    uint64_t ptr;
    int32_t arch;   // Architecture: 1 == 64-bit
    char mPad[4];
};
static_assert(sizeof(DebugInfo) == 24, "DebugInfo");

// Declaration order is the generated header's: it fixes the vtable slots.
struct IBase : virtual public ::android::RefBase {
    virtual bool isRemote() const { return false; }
    virtual ::android::hardware::Return<void> interfaceChain(
        std::function<void(const ::android::hardware::hidl_vec< ::android::hardware::hidl_string>&)> cb);
    virtual ::android::hardware::Return<void> debug(
        const ::android::hardware::hidl_handle& fd,
        const ::android::hardware::hidl_vec< ::android::hardware::hidl_string>& options);
    virtual ::android::hardware::Return<void> interfaceDescriptor(
        std::function<void(const ::android::hardware::hidl_string&)> cb);
    virtual ::android::hardware::Return<void> getHashChain(
        std::function<void(const ::android::hardware::hidl_vec< ::android::hardware::hidl_array<uint8_t, 32>>&)> cb);
    virtual ::android::hardware::Return<void> setHALInstrumentation();
    virtual ::android::hardware::Return<bool> linkToDeath(
        const ::android::sp< ::android::hardware::hidl_death_recipient>& recipient, uint64_t cookie);
    virtual ::android::hardware::Return<void> ping();
    virtual ::android::hardware::Return<void> getDebugInfo(
        std::function<void(const DebugInfo&)> cb);
    virtual ::android::hardware::Return<void> notifySyspropsChanged();
    virtual ::android::hardware::Return<bool> unlinkToDeath(
        const ::android::sp< ::android::hardware::hidl_death_recipient>& recipient);
};

} } } }  // namespace android::hidl::base::V1_0

// ---------------------------------------------------------------------------
// Radio types (wire layouts from radio/1.0/types.hal and radio/1.2/types.hal).
// ---------------------------------------------------------------------------
namespace android { namespace hardware { namespace radio { namespace V1_0 {

enum RadioErrorE : int32_t {
    RE_NONE = 0, RE_RADIO_NOT_AVAILABLE = 1, RE_GENERIC_FAILURE = 2,
    RE_REQUEST_NOT_SUPPORTED = 6, RE_SIM_ABSENT = 11
};
enum : int32_t { RESP_SOLICITED = 0, IND_UNSOLICITED = 0 };
enum : int32_t { CARD_ABSENT = 0, CARD_PRESENT = 1 };
enum : int32_t { APP_USIM = 2, APP_STATE_READY = 5, PIN_UNKNOWN = 0 };
enum : int32_t { REG_NOT_REG = 0, REG_HOME = 1 };
enum : int32_t { RADIO_OFF = 0, RADIO_UNAVAILABLE = 1, RADIO_ON = 10 };
enum : int32_t { OP_CURRENT = 2 };

struct RadioResponseInfo { int32_t type; int32_t serial; int32_t error; };
static_assert(sizeof(RadioResponseInfo) == 12, "RadioResponseInfo");

struct AppStatus {
    int32_t appType; int32_t appState; int32_t persoSubstate;
    hidl_string aidPtr; hidl_string appLabelPtr;
    int32_t pin1Replaced; int32_t pin1; int32_t pin2;
};
static_assert(sizeof(AppStatus) == 64, "AppStatus");

struct CardStatus {
    int32_t cardState; int32_t universalPinState;
    int32_t gsmUmtsSubscriptionAppIndex; int32_t cdmaSubscriptionAppIndex;
    int32_t imsSubscriptionAppIndex;
    hidl_vec<AppStatus> applications;
};
static_assert(sizeof(CardStatus) == 40, "CardStatus");

struct IccIo {
    int32_t command; int32_t fileId;
    hidl_string path;
    int32_t p1; int32_t p2; int32_t p3;
    hidl_string data; hidl_string pin2; hidl_string aid;
};
static_assert(sizeof(IccIo) == 88, "IccIo");

struct IccIoResult { int32_t sw1; int32_t sw2; hidl_string simResponse; };
static_assert(sizeof(IccIoResult) == 24, "IccIoResult");

struct IccOpenLogicalChannelResponse { int32_t channelId; hidl_string selectResponse; };
static_assert(sizeof(IccOpenLogicalChannelResponse) == 24, "IccOpenLogicalChannelResponse");

struct CellIdentityGsm {
    hidl_string mcc; hidl_string mnc;
    int32_t lac; int32_t cid; int32_t arfcn; uint8_t bsic; char mPad[3];
};
static_assert(sizeof(CellIdentityGsm) == 48, "CellIdentityGsm");
struct CellIdentityCdma {
    int32_t networkId; int32_t systemId; int32_t baseStationId; int32_t longitude; int32_t latitude;
};
static_assert(sizeof(CellIdentityCdma) == 20, "CellIdentityCdma");
struct CellIdentityLte {
    hidl_string mcc; hidl_string mnc;
    int32_t ci; int32_t pci; int32_t tac; int32_t earfcn;
};
static_assert(sizeof(CellIdentityLte) == 48, "CellIdentityLte");
struct CellIdentityWcdma {
    hidl_string mcc; hidl_string mnc;
    int32_t lac; int32_t cid; int32_t psc; int32_t uarfcn;
};
static_assert(sizeof(CellIdentityWcdma) == 48, "CellIdentityWcdma");
struct CellIdentityTdscdma {
    hidl_string mcc; hidl_string mnc;
    int32_t lac; int32_t cid; int32_t cpid;
};
static_assert(sizeof(CellIdentityTdscdma) == 48, "CellIdentityTdscdma");
struct CellIdentity {
    int32_t cellInfoType;
    hidl_vec<CellIdentityGsm> cellIdentityGsm;
    hidl_vec<CellIdentityCdma> cellIdentityCdma;
    hidl_vec<CellIdentityLte> cellIdentityLte;
    hidl_vec<CellIdentityWcdma> cellIdentityWcdma;
    hidl_vec<CellIdentityTdscdma> cellIdentityTdscdma;
};
static_assert(sizeof(CellIdentity) == 88, "CellIdentity");

struct VoiceRegStateResult {
    int32_t regState; int32_t rat; bool cssSupported; char mPad0[3];
    int32_t roamingIndicator; int32_t systemIsInPrl; int32_t defaultRoamingIndicator;
    int32_t reasonForDenial;
    CellIdentity cellIdentity;
};
static_assert(sizeof(VoiceRegStateResult) == 120, "VoiceRegStateResult");
struct DataRegStateResult {
    int32_t regState; int32_t rat; int32_t reasonDataDenied; int32_t maxDataCalls;
    CellIdentity cellIdentity;
};
static_assert(sizeof(DataRegStateResult) == 104, "DataRegStateResult");

struct GsmSignalStrength { uint32_t signalStrength; uint32_t bitErrorRate; int32_t timingAdvance; };
struct CdmaSignalStrength { uint32_t dbm; uint32_t ecio; };
struct EvdoSignalStrength { uint32_t dbm; uint32_t ecio; uint32_t signalNoiseRatio; };
struct LteSignalStrength { uint32_t signalStrength; uint32_t rsrp; uint32_t rsrq; int32_t rssnr; uint32_t cqi; uint32_t timingAdvance; };
struct TdScdmaSignalStrength { uint32_t rscp; };
struct SignalStrength {
    GsmSignalStrength gw; CdmaSignalStrength cdma; EvdoSignalStrength evdo;
    LteSignalStrength lte; TdScdmaSignalStrength tdScdma;
};
static_assert(sizeof(SignalStrength) == 60, "SignalStrength");

struct SetupDataCallResult {
    int32_t status; int32_t suggestedRetryTime; int32_t cid; int32_t active;
    hidl_string type; hidl_string ifname; hidl_string addresses; hidl_string dnses;
    hidl_string gateways; hidl_string pcscf; int32_t mtu;
};
static_assert(sizeof(SetupDataCallResult) == 120, "SetupDataCallResult");

struct RadioCapability {
    int32_t session; int32_t phase; uint32_t raf; hidl_string logicalModemUuid; int32_t status;
};
static_assert(sizeof(RadioCapability) == 40, "RadioCapability");

struct SendSmsResult { int32_t messageRef; hidl_string ackPDU; int32_t errorCode; };
static_assert(sizeof(SendSmsResult) == 32, "SendSmsResult");

struct LastCallFailCauseInfo { int32_t causeCode; hidl_string vendorCause; };
static_assert(sizeof(LastCallFailCauseInfo) == 24, "LastCallFailCauseInfo");

struct OperatorInfo {
    hidl_string alphaLong; hidl_string alphaShort; hidl_string operatorNumeric; int32_t status;
};
static_assert(sizeof(OperatorInfo) == 56, "OperatorInfo");

struct CallForwardInfo {
    int32_t status; int32_t reason; int32_t serviceClass; int32_t toa;
    hidl_string number; int32_t timeSeconds;
};
static_assert(sizeof(CallForwardInfo) == 40, "CallForwardInfo");

struct NeighboringCell { hidl_string cid; int32_t rssi; };
static_assert(sizeof(NeighboringCell) == 24, "NeighboringCell");

struct CdmaSignalInfoRecord { bool isPresent; char mPad[3]; int32_t signalType; int32_t alertPitch; int32_t signal; };
static_assert(sizeof(CdmaSignalInfoRecord) == 16, "CdmaSignalInfoRecord");

struct Carrier { uint8_t dummy; };
struct CarrierRestrictions { hidl_vec<Carrier> allowedCarriers; hidl_vec<Carrier> excludedCarriers; };
static_assert(sizeof(CarrierRestrictions) == 32, "CarrierRestrictions");

struct LceStatusInfo { int32_t lceStatus; uint8_t actualIntervalMs; char mPad[3]; };
struct LceDataInfo { uint32_t lastHopCapacityKbps; uint8_t confidenceLevel; bool lceSuspended; char mPad[2]; };
struct ActivityStatsInfo {
    uint32_t sleepModeTimeMs; uint32_t idleModeTimeMs; uint32_t txmModetimeMs[5]; uint32_t rxModeTimeMs;
};
static_assert(sizeof(ActivityStatsInfo) == 32, "ActivityStatsInfo");

// Element types only ever used behind an empty vec in this service.
struct Call { uint8_t dummy[8]; };
struct CellInfo { uint8_t dummy[8]; };
struct HardwareConfig { uint8_t dummy[8]; };
struct GsmBroadcastSmsConfigInfo { uint8_t dummy[8]; };
struct CdmaBroadcastSmsConfigInfo { uint8_t dummy[8]; };

} } } }  // namespace android::hardware::radio::V1_0

// ---------------------------------------------------------------------------
// Callback interfaces. Only declared (the objects belong to the framework); the
// declarations exist so this file can call the methods it uses, with the generated
// signatures. Declaration order is the vtable order.
// ---------------------------------------------------------------------------
namespace android { namespace hardware { namespace radio { namespace V1_0 {

using ::android::hardware::Return;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;

struct IRadioResponse : virtual public ::android::hidl::base::V1_0::IBase {
    virtual Return<void> getIccCardStatusResponse(const RadioResponseInfo& info, const CardStatus& cardStatus);
    virtual Return<void> supplyIccPinForAppResponse(const RadioResponseInfo& info, int32_t remainingRetries);
    virtual Return<void> supplyIccPukForAppResponse(const RadioResponseInfo& info, int32_t remainingRetries);
    virtual Return<void> supplyIccPin2ForAppResponse(const RadioResponseInfo& info, int32_t remainingRetries);
    virtual Return<void> supplyIccPuk2ForAppResponse(const RadioResponseInfo& info, int32_t remainingRetries);
    virtual Return<void> changeIccPinForAppResponse(const RadioResponseInfo& info, int32_t remainingRetries);
    virtual Return<void> changeIccPin2ForAppResponse(const RadioResponseInfo& info, int32_t remainingRetries);
    virtual Return<void> supplyNetworkDepersonalizationResponse(const RadioResponseInfo& info, int32_t remainingRetries);
    virtual Return<void> getCurrentCallsResponse(const RadioResponseInfo& info, const hidl_vec<Call>& calls);
    virtual Return<void> dialResponse(const RadioResponseInfo& info);
    virtual Return<void> getIMSIForAppResponse(const RadioResponseInfo& info, const hidl_string& imsi);
    virtual Return<void> hangupConnectionResponse(const RadioResponseInfo& info);
    virtual Return<void> hangupWaitingOrBackgroundResponse(const RadioResponseInfo& info);
    virtual Return<void> hangupForegroundResumeBackgroundResponse(const RadioResponseInfo& info);
    virtual Return<void> switchWaitingOrHoldingAndActiveResponse(const RadioResponseInfo& info);
    virtual Return<void> conferenceResponse(const RadioResponseInfo& info);
    virtual Return<void> rejectCallResponse(const RadioResponseInfo& info);
    virtual Return<void> getLastCallFailCauseResponse(const RadioResponseInfo& info, const LastCallFailCauseInfo& failCauseInfo);
    virtual Return<void> getSignalStrengthResponse(const RadioResponseInfo& info, const SignalStrength& signalStrength);
    virtual Return<void> getVoiceRegistrationStateResponse(const RadioResponseInfo& info, const VoiceRegStateResult& voiceRegStateResult);
    virtual Return<void> getDataRegistrationStateResponse(const RadioResponseInfo& info, const DataRegStateResult& dataRegStateResult);
    virtual Return<void> getOperatorResponse(const RadioResponseInfo& info, const hidl_string& longName, const hidl_string& shortName, const hidl_string& numeric);
    virtual Return<void> setRadioPowerResponse(const RadioResponseInfo& info);
    virtual Return<void> sendDtmfResponse(const RadioResponseInfo& info);
    virtual Return<void> sendSmsResponse(const RadioResponseInfo& info, const SendSmsResult& sms);
    virtual Return<void> sendSMSExpectMoreResponse(const RadioResponseInfo& info, const SendSmsResult& sms);
    virtual Return<void> setupDataCallResponse(const RadioResponseInfo& info, const SetupDataCallResult& dcResponse);
    virtual Return<void> iccIOForAppResponse(const RadioResponseInfo& info, const IccIoResult& iccIoResult);
    virtual Return<void> sendUssdResponse(const RadioResponseInfo& info);
    virtual Return<void> cancelPendingUssdResponse(const RadioResponseInfo& info);
    virtual Return<void> getClirResponse(const RadioResponseInfo& info, int32_t n, int32_t m);
    virtual Return<void> setClirResponse(const RadioResponseInfo& info);
    virtual Return<void> getCallForwardStatusResponse(const RadioResponseInfo& info, const hidl_vec<CallForwardInfo>& callForwardInfos);
    virtual Return<void> setCallForwardResponse(const RadioResponseInfo& info);
    virtual Return<void> getCallWaitingResponse(const RadioResponseInfo& info, bool enable, int32_t serviceClass);
    virtual Return<void> setCallWaitingResponse(const RadioResponseInfo& info);
    virtual Return<void> acknowledgeLastIncomingGsmSmsResponse(const RadioResponseInfo& info);
    virtual Return<void> acceptCallResponse(const RadioResponseInfo& info);
    virtual Return<void> deactivateDataCallResponse(const RadioResponseInfo& info);
    virtual Return<void> getFacilityLockForAppResponse(const RadioResponseInfo& info, int32_t response);
    virtual Return<void> setFacilityLockForAppResponse(const RadioResponseInfo& info);
    virtual Return<void> setBarringPasswordResponse(const RadioResponseInfo& info);
    virtual Return<void> getNetworkSelectionModeResponse(const RadioResponseInfo& info, int32_t mode);
    virtual Return<void> setNetworkSelectionModeAutomaticResponse(const RadioResponseInfo& info);
    virtual Return<void> setNetworkSelectionModeManualResponse(const RadioResponseInfo& info);
    virtual Return<void> getAvailableNetworksResponse(const RadioResponseInfo& info, const hidl_vec<OperatorInfo>& networkInfos);
    virtual Return<void> startDtmfResponse(const RadioResponseInfo& info);
    virtual Return<void> stopDtmfResponse(const RadioResponseInfo& info);
    virtual Return<void> getBasebandVersionResponse(const RadioResponseInfo& info, const hidl_string& version);
    virtual Return<void> separateConnectionResponse(const RadioResponseInfo& info);
    virtual Return<void> setMuteResponse(const RadioResponseInfo& info);
    virtual Return<void> getMuteResponse(const RadioResponseInfo& info, bool response);
    virtual Return<void> getClipResponse(const RadioResponseInfo& info, int32_t status);
    virtual Return<void> getDataCallListResponse(const RadioResponseInfo& info, const hidl_vec<SetupDataCallResult>& dcList);
    virtual Return<void> setSuppServiceNotificationsResponse(const RadioResponseInfo& info);
    virtual Return<void> writeSmsToSimResponse(const RadioResponseInfo& info, int32_t index);
    virtual Return<void> deleteSmsOnSimResponse(const RadioResponseInfo& info);
    virtual Return<void> setBandModeResponse(const RadioResponseInfo& info);
    virtual Return<void> getAvailableBandModesResponse(const RadioResponseInfo& info, const hidl_vec<int32_t>& bandModes);
    virtual Return<void> sendEnvelopeResponse(const RadioResponseInfo& info, const IccIoResult& result);
    virtual Return<void> sendTerminalResponseToSimResponse(const RadioResponseInfo& info, const IccIoResult& result);
    virtual Return<void> handleStkCallSetupRequestFromSimResponse(const RadioResponseInfo& info);
    virtual Return<void> explicitCallTransferResponse(const RadioResponseInfo& info);
    virtual Return<void> setPreferredNetworkTypeResponse(const RadioResponseInfo& info);
    virtual Return<void> getPreferredNetworkTypeResponse(const RadioResponseInfo& info, int32_t nwType);
    virtual Return<void> getNeighboringCidsResponse(const RadioResponseInfo& info, const hidl_vec<NeighboringCell>& cells);
    virtual Return<void> setLocationUpdatesResponse(const RadioResponseInfo& info);
    virtual Return<void> setCdmaSubscriptionSourceResponse(const RadioResponseInfo& info);
    virtual Return<void> setCdmaRoamingPreferenceResponse(const RadioResponseInfo& info);
    virtual Return<void> getCdmaRoamingPreferenceResponse(const RadioResponseInfo& info, int32_t type);
    virtual Return<void> setTTYModeResponse(const RadioResponseInfo& info);
    virtual Return<void> getTTYModeResponse(const RadioResponseInfo& info, int32_t mode);
    virtual Return<void> setPreferredVoicePrivacyResponse(const RadioResponseInfo& info);
    virtual Return<void> getPreferredVoicePrivacyResponse(const RadioResponseInfo& info, bool response);
    virtual Return<void> sendCDMAFeatureCodeResponse(const RadioResponseInfo& info);
    virtual Return<void> sendBurstDtmfResponse(const RadioResponseInfo& info);
    virtual Return<void> sendCdmaSmsResponse(const RadioResponseInfo& info, const SendSmsResult& sms);
    virtual Return<void> acknowledgeLastIncomingCdmaSmsResponse(const RadioResponseInfo& info);
    virtual Return<void> getGsmBroadcastConfigResponse(const RadioResponseInfo& info, const hidl_vec<GsmBroadcastSmsConfigInfo>& configs);
    virtual Return<void> setGsmBroadcastConfigResponse(const RadioResponseInfo& info);
    virtual Return<void> setGsmBroadcastActivationResponse(const RadioResponseInfo& info);
    virtual Return<void> getCdmaBroadcastConfigResponse(const RadioResponseInfo& info, const hidl_vec<CdmaBroadcastSmsConfigInfo>& configs);
    virtual Return<void> setCdmaBroadcastConfigResponse(const RadioResponseInfo& info);
    virtual Return<void> setCdmaBroadcastActivationResponse(const RadioResponseInfo& info);
    virtual Return<void> getCDMASubscriptionResponse(const RadioResponseInfo& info, const hidl_string& mdn, const hidl_string& hSid, const hidl_string& hNid, const hidl_string& min, const hidl_string& prl);
    virtual Return<void> writeSmsToRuimResponse(const RadioResponseInfo& info, int32_t index);
    virtual Return<void> deleteSmsOnRuimResponse(const RadioResponseInfo& info);
    virtual Return<void> getDeviceIdentityResponse(const RadioResponseInfo& info, const hidl_string& imei, const hidl_string& imeisv, const hidl_string& esn, const hidl_string& meid);
    virtual Return<void> exitEmergencyCallbackModeResponse(const RadioResponseInfo& info);
    virtual Return<void> getSmscAddressResponse(const RadioResponseInfo& info, const hidl_string& smsc);
    virtual Return<void> setSmscAddressResponse(const RadioResponseInfo& info);
    virtual Return<void> reportSmsMemoryStatusResponse(const RadioResponseInfo& info);
    virtual Return<void> reportStkServiceIsRunningResponse(const RadioResponseInfo& info);
    virtual Return<void> getCdmaSubscriptionSourceResponse(const RadioResponseInfo& info, int32_t source);
    virtual Return<void> requestIsimAuthenticationResponse(const RadioResponseInfo& info, const IccIoResult& result);
    virtual Return<void> acknowledgeIncomingGsmSmsWithPduResponse(const RadioResponseInfo& info);
    virtual Return<void> sendEnvelopeWithStatusResponse(const RadioResponseInfo& info, const IccIoResult& result);
    virtual Return<void> getVoiceRadioTechnologyResponse(const RadioResponseInfo& info, int32_t rat);
    virtual Return<void> getCellInfoListResponse(const RadioResponseInfo& info, const hidl_vec<CellInfo>& cellInfo);
    virtual Return<void> setCellInfoListRateResponse(const RadioResponseInfo& info);
    virtual Return<void> setInitialAttachApnResponse(const RadioResponseInfo& info);
    virtual Return<void> getImsRegistrationStateResponse(const RadioResponseInfo& info, bool isRegistered, int32_t ratFamily);
    virtual Return<void> sendImsSmsResponse(const RadioResponseInfo& info, const SendSmsResult& result);
    virtual Return<void> iccTransmitApduBasicChannelResponse(const RadioResponseInfo& info, const IccIoResult& result);
    virtual Return<void> iccOpenLogicalChannelResponse(const RadioResponseInfo& info, const IccOpenLogicalChannelResponse& result);
    virtual Return<void> iccCloseLogicalChannelResponse(const RadioResponseInfo& info);
    virtual Return<void> iccTransmitApduLogicalChannelResponse(const RadioResponseInfo& info, const IccIoResult& result);
    virtual Return<void> nvReadItemResponse(const RadioResponseInfo& info, const hidl_string& result);
    virtual Return<void> nvWriteItemResponse(const RadioResponseInfo& info);
    virtual Return<void> nvWriteCdmaPrlResponse(const RadioResponseInfo& info);
    virtual Return<void> nvResetConfigResponse(const RadioResponseInfo& info);
    virtual Return<void> setUiccSubscriptionResponse(const RadioResponseInfo& info);
    virtual Return<void> setDataAllowedResponse(const RadioResponseInfo& info);
    virtual Return<void> getHardwareConfigResponse(const RadioResponseInfo& info, const hidl_vec<HardwareConfig>& result);
    virtual Return<void> requestIccSimAuthenticationResponse(const RadioResponseInfo& info, const IccIoResult& result);
    virtual Return<void> setDataProfileResponse(const RadioResponseInfo& info);
    virtual Return<void> requestShutdownResponse(const RadioResponseInfo& info);
    virtual Return<void> getRadioCapabilityResponse(const RadioResponseInfo& info, const RadioCapability& rc);
    virtual Return<void> setRadioCapabilityResponse(const RadioResponseInfo& info, const RadioCapability& rc);
    virtual Return<void> startLceServiceResponse(const RadioResponseInfo& info, const LceStatusInfo& statusInfo);
    virtual Return<void> stopLceServiceResponse(const RadioResponseInfo& info, const LceStatusInfo& statusInfo);
    virtual Return<void> pullLceDataResponse(const RadioResponseInfo& info, const LceDataInfo& lceInfo);
    virtual Return<void> getModemActivityInfoResponse(const RadioResponseInfo& info, const ActivityStatsInfo& info_);
    virtual Return<void> setAllowedCarriersResponse(const RadioResponseInfo& info);
    virtual Return<void> getAllowedCarriersResponse(const RadioResponseInfo& info, bool allAllowed, const CarrierRestrictions& carriers);
    virtual Return<void> sendDeviceStateResponse(const RadioResponseInfo& info);
    virtual Return<void> setIndicationFilterResponse(const RadioResponseInfo& info);
    virtual Return<void> setSimCardPowerResponse(const RadioResponseInfo& info);
    virtual Return<void> acknowledgeRequest(int32_t serial);
};

struct IRadioIndication : virtual public ::android::hidl::base::V1_0::IBase {
    virtual Return<void> radioStateChanged(int32_t type, int32_t radioState);
    virtual Return<void> callStateChanged(int32_t type);
    virtual Return<void> networkStateChanged(int32_t type);
    virtual Return<void> newSms(int32_t type, const hidl_vec<uint8_t>& pdu);
    virtual Return<void> newSmsStatusReport(int32_t type);
    virtual Return<void> newSmsOnSim(int32_t type);
    virtual Return<void> onUssd(int32_t type);
    virtual Return<void> nitzTimeReceived(int32_t type);
    virtual Return<void> currentSignalStrength(int32_t type, const SignalStrength& signalStrength);
    virtual Return<void> dataCallListChanged(int32_t type);
    virtual Return<void> suppSvcNotify(int32_t type);
    virtual Return<void> stkSessionEnd(int32_t type);
    virtual Return<void> stkProactiveCommand(int32_t type);
    virtual Return<void> stkEventNotify(int32_t type);
    virtual Return<void> stkCallSetup(int32_t type);
    virtual Return<void> simSmsStorageFull(int32_t type);
    virtual Return<void> simRefresh(int32_t type);
    virtual Return<void> callRing(int32_t type, bool isGsm, const CdmaSignalInfoRecord& record);
    virtual Return<void> simStatusChanged(int32_t type);
    virtual Return<void> cdmaNewSms(int32_t type);
    virtual Return<void> newBroadcastSms(int32_t type);
    virtual Return<void> cdmaRuimSmsStorageFull(int32_t type);
    virtual Return<void> restrictedStateChanged(int32_t type);
    virtual Return<void> enterEmergencyCallbackMode(int32_t type);
    virtual Return<void> cdmaCallWaiting(int32_t type);
    virtual Return<void> cdmaOtaProvisionStatus(int32_t type);
    virtual Return<void> cdmaInfoRec(int32_t type);
    virtual Return<void> resendIncallMute(int32_t type);
    virtual Return<void> cdmaSubscriptionSourceChanged(int32_t type);
    virtual Return<void> stkCallControlAlphaNotify(int32_t type);
    virtual Return<void> lceData(int32_t type);
    virtual Return<void> pcoData(int32_t type);
    virtual Return<void> modemReset(int32_t type);
};

} } } }  // namespace android::hardware::radio::V1_0

// ---------------------------------------------------------------------------
// IRadio chain. Each version struct declares only its own methods, in chain order,
// exactly like the generated headers. Signatures are the real ones only where the
// implementation reads the arguments; elsewhere (int32_t serial) suffices — the
// vtable is fixed by declaration order, and un-read arguments are simply ignored.
// ---------------------------------------------------------------------------
namespace android { namespace hardware { namespace radio {

namespace V1_0 {
using ::android::sp;
struct IRadio : virtual public ::android::hidl::base::V1_0::IBase {
    virtual Return<void> setResponseFunctions(const sp<IRadioResponse>& radioResponse, const sp<IRadioIndication>& radioIndication);
    virtual Return<void> getIccCardStatus(int32_t serial);
    virtual Return<void> supplyIccPinForApp(int32_t serial);
    virtual Return<void> supplyIccPukForApp(int32_t serial);
    virtual Return<void> supplyIccPin2ForApp(int32_t serial);
    virtual Return<void> supplyIccPuk2ForApp(int32_t serial);
    virtual Return<void> changeIccPinForApp(int32_t serial);
    virtual Return<void> changeIccPin2ForApp(int32_t serial);
    virtual Return<void> supplyNetworkDepersonalization(int32_t serial);
    virtual Return<void> getCurrentCalls(int32_t serial);
    virtual Return<void> dial(int32_t serial);
    virtual Return<void> getImsiForApp(int32_t serial, const hidl_string& aid);
    virtual Return<void> hangup(int32_t serial);
    virtual Return<void> hangupWaitingOrBackground(int32_t serial);
    virtual Return<void> hangupForegroundResumeBackground(int32_t serial);
    virtual Return<void> switchWaitingOrHoldingAndActive(int32_t serial);
    virtual Return<void> conference(int32_t serial);
    virtual Return<void> rejectCall(int32_t serial);
    virtual Return<void> getLastCallFailCause(int32_t serial);
    virtual Return<void> getSignalStrength(int32_t serial);
    virtual Return<void> getVoiceRegistrationState(int32_t serial);
    virtual Return<void> getDataRegistrationState(int32_t serial);
    virtual Return<void> getOperator(int32_t serial);
    virtual Return<void> setRadioPower(int32_t serial, bool on);
    virtual Return<void> sendDtmf(int32_t serial);
    virtual Return<void> sendSms(int32_t serial);
    virtual Return<void> sendSMSExpectMore(int32_t serial);
    virtual Return<void> setupDataCall(int32_t serial);
    virtual Return<void> iccIOForApp(int32_t serial, const IccIo& iccIo);
    virtual Return<void> sendUssd(int32_t serial);
    virtual Return<void> cancelPendingUssd(int32_t serial);
    virtual Return<void> getClir(int32_t serial);
    virtual Return<void> setClir(int32_t serial);
    virtual Return<void> getCallForwardStatus(int32_t serial);
    virtual Return<void> setCallForward(int32_t serial);
    virtual Return<void> getCallWaiting(int32_t serial);
    virtual Return<void> setCallWaiting(int32_t serial);
    virtual Return<void> acknowledgeLastIncomingGsmSms(int32_t serial);
    virtual Return<void> acceptCall(int32_t serial);
    virtual Return<void> deactivateDataCall(int32_t serial);
    virtual Return<void> getFacilityLockForApp(int32_t serial);
    virtual Return<void> setFacilityLockForApp(int32_t serial);
    virtual Return<void> setBarringPassword(int32_t serial);
    virtual Return<void> getNetworkSelectionMode(int32_t serial);
    virtual Return<void> setNetworkSelectionModeAutomatic(int32_t serial);
    virtual Return<void> setNetworkSelectionModeManual(int32_t serial);
    virtual Return<void> getAvailableNetworks(int32_t serial);
    virtual Return<void> startDtmf(int32_t serial);
    virtual Return<void> stopDtmf(int32_t serial);
    virtual Return<void> getBasebandVersion(int32_t serial);
    virtual Return<void> separateConnection(int32_t serial);
    virtual Return<void> setMute(int32_t serial);
    virtual Return<void> getMute(int32_t serial);
    virtual Return<void> getClip(int32_t serial);
    virtual Return<void> getDataCallList(int32_t serial);
    virtual Return<void> setSuppServiceNotifications(int32_t serial);
    virtual Return<void> writeSmsToSim(int32_t serial);
    virtual Return<void> deleteSmsOnSim(int32_t serial);
    virtual Return<void> setBandMode(int32_t serial);
    virtual Return<void> getAvailableBandModes(int32_t serial);
    virtual Return<void> sendEnvelope(int32_t serial);
    virtual Return<void> sendTerminalResponseToSim(int32_t serial);
    virtual Return<void> handleStkCallSetupRequestFromSim(int32_t serial);
    virtual Return<void> explicitCallTransfer(int32_t serial);
    virtual Return<void> setPreferredNetworkType(int32_t serial);
    virtual Return<void> getPreferredNetworkType(int32_t serial);
    virtual Return<void> getNeighboringCids(int32_t serial);
    virtual Return<void> setLocationUpdates(int32_t serial);
    virtual Return<void> setCdmaSubscriptionSource(int32_t serial);
    virtual Return<void> setCdmaRoamingPreference(int32_t serial);
    virtual Return<void> getCdmaRoamingPreference(int32_t serial);
    virtual Return<void> setTTYMode(int32_t serial);
    virtual Return<void> getTTYMode(int32_t serial);
    virtual Return<void> setPreferredVoicePrivacy(int32_t serial);
    virtual Return<void> getPreferredVoicePrivacy(int32_t serial);
    virtual Return<void> sendCDMAFeatureCode(int32_t serial);
    virtual Return<void> sendBurstDtmf(int32_t serial);
    virtual Return<void> sendCdmaSms(int32_t serial);
    virtual Return<void> acknowledgeLastIncomingCdmaSms(int32_t serial);
    virtual Return<void> getGsmBroadcastConfig(int32_t serial);
    virtual Return<void> setGsmBroadcastConfig(int32_t serial);
    virtual Return<void> setGsmBroadcastActivation(int32_t serial);
    virtual Return<void> getCdmaBroadcastConfig(int32_t serial);
    virtual Return<void> setCdmaBroadcastConfig(int32_t serial);
    virtual Return<void> setCdmaBroadcastActivation(int32_t serial);
    virtual Return<void> getCDMASubscription(int32_t serial);
    virtual Return<void> writeSmsToRuim(int32_t serial);
    virtual Return<void> deleteSmsOnRuim(int32_t serial);
    virtual Return<void> getDeviceIdentity(int32_t serial);
    virtual Return<void> exitEmergencyCallbackMode(int32_t serial);
    virtual Return<void> getSmscAddress(int32_t serial);
    virtual Return<void> setSmscAddress(int32_t serial);
    virtual Return<void> reportSmsMemoryStatus(int32_t serial);
    virtual Return<void> reportStkServiceIsRunning(int32_t serial);
    virtual Return<void> getCdmaSubscriptionSource(int32_t serial);
    virtual Return<void> requestIsimAuthentication(int32_t serial);
    virtual Return<void> acknowledgeIncomingGsmSmsWithPdu(int32_t serial);
    virtual Return<void> sendEnvelopeWithStatus(int32_t serial);
    virtual Return<void> getVoiceRadioTechnology(int32_t serial);
    virtual Return<void> getCellInfoList(int32_t serial);
    virtual Return<void> setCellInfoListRate(int32_t serial);
    virtual Return<void> setInitialAttachApn(int32_t serial);
    virtual Return<void> getImsRegistrationState(int32_t serial);
    virtual Return<void> sendImsSms(int32_t serial);
    virtual Return<void> iccTransmitApduBasicChannel(int32_t serial);
    virtual Return<void> iccOpenLogicalChannel(int32_t serial);
    virtual Return<void> iccCloseLogicalChannel(int32_t serial);
    virtual Return<void> iccTransmitApduLogicalChannel(int32_t serial);
    virtual Return<void> nvReadItem(int32_t serial);
    virtual Return<void> nvWriteItem(int32_t serial);
    virtual Return<void> nvWriteCdmaPrl(int32_t serial);
    virtual Return<void> nvResetConfig(int32_t serial);
    virtual Return<void> setUiccSubscription(int32_t serial);
    virtual Return<void> setDataAllowed(int32_t serial);
    virtual Return<void> getHardwareConfig(int32_t serial);
    virtual Return<void> requestIccSimAuthentication(int32_t serial);
    virtual Return<void> setDataProfile(int32_t serial);
    virtual Return<void> requestShutdown(int32_t serial);
    virtual Return<void> getRadioCapability(int32_t serial);
    virtual Return<void> setRadioCapability(int32_t serial);
    virtual Return<void> startLceService(int32_t serial);
    virtual Return<void> stopLceService(int32_t serial);
    virtual Return<void> pullLceData(int32_t serial);
    virtual Return<void> getModemActivityInfo(int32_t serial);
    virtual Return<void> setAllowedCarriers(int32_t serial);
    virtual Return<void> getAllowedCarriers(int32_t serial);
    virtual Return<void> sendDeviceState(int32_t serial);
    virtual Return<void> setIndicationFilter(int32_t serial);
    virtual Return<void> setSimCardPower(int32_t serial);
    virtual Return<void> responseAcknowledgement(int32_t serial);
};
}  // namespace V1_0

namespace V1_1 {
struct IRadio : public V1_0::IRadio {
    virtual Return<void> setCarrierInfoForImsiEncryption(int32_t serial);
    virtual Return<void> setSimCardPower_1_1(int32_t serial, bool powerUp);
    virtual Return<void> startNetworkScan(int32_t serial);
    virtual Return<void> stopNetworkScan(int32_t serial);
    virtual Return<void> startKeepalive(int32_t serial);
    virtual Return<void> stopKeepalive(int32_t serial);
};
}  // namespace V1_1

namespace V1_2 {
struct IRadio : public V1_1::IRadio {
    virtual Return<void> startNetworkScan_1_2(int32_t serial);
    virtual Return<void> setIndicationFilter_1_2(int32_t serial);
    virtual Return<void> setSignalStrengthReportingCriteria(int32_t serial);
    virtual Return<void> setLinkCapacityReportingCriteria(int32_t serial);
    virtual Return<void> setupDataCall_1_2(int32_t serial);
    virtual Return<void> deactivateDataCall_1_2(int32_t serial);
};
}  // namespace V1_2

namespace V1_3 {
struct IRadio : public V1_2::IRadio {
    virtual Return<void> setSystemSelectionChannels(int32_t serial);
    virtual Return<void> enableModem(int32_t serial);
    virtual Return<void> getModemStackStatus(int32_t serial);
};
}  // namespace V1_3

namespace V1_4 {
struct IRadio : public V1_3::IRadio {
    virtual Return<void> setupDataCall_1_4(int32_t serial);
    virtual Return<void> setInitialAttachApn_1_4(int32_t serial);
    virtual Return<void> setDataProfile_1_4(int32_t serial);
    virtual Return<void> emergencyDial(int32_t serial);
    virtual Return<void> startNetworkScan_1_4(int32_t serial);
    virtual Return<void> getPreferredNetworkTypeBitmap(int32_t serial);
    virtual Return<void> setPreferredNetworkTypeBitmap(int32_t serial);
    virtual Return<void> setAllowedCarriers_1_4(int32_t serial);
    virtual Return<void> getAllowedCarriers_1_4(int32_t serial);
    virtual Return<void> getSignalStrength_1_4(int32_t serial);
};
}  // namespace V1_4

} } }  // namespace android::hardware::radio

// ---------------------------------------------------------------------------
// Legacy RIL parcel codec — byte-for-byte the dialect RilStub speaks (and the 6.0
// guest's libril): little-endian ints, strings as UTF-16LE with a character count
// and 4-byte alignment.
// ---------------------------------------------------------------------------
struct PWriter {
    std::vector<uint8_t> b;
    void i32(int32_t v) {
        b.push_back((uint8_t)(v & 0xFF)); b.push_back((uint8_t)((v >> 8) & 0xFF));
        b.push_back((uint8_t)((v >> 16) & 0xFF)); b.push_back((uint8_t)((v >> 24) & 0xFF));
    }
    void str(const std::string& s) {
        i32((int32_t)s.size());
        for (char c : s) { b.push_back((uint8_t)c); b.push_back(0); }
        b.push_back(0); b.push_back(0);   // NUL terminator
        while ((b.size() & 3) != 0) b.push_back(0);
    }
};

struct PReader {
    const uint8_t* p; size_t len; size_t pos = 0; bool ok = true;
    PReader(const uint8_t* p_, size_t len_) : p(p_), len(len_) {}
    int32_t i32() {
        if (pos + 4 > len) { ok = false; return 0; }
        int32_t v; memcpy(&v, p + pos, 4); pos += 4; return v;
    }
    std::string str() {
        int32_t n = i32();
        if (n < 0) return std::string();
        std::string out;
        if (pos + (size_t)n * 2 <= len) {
            out.reserve((size_t)n);
            for (int32_t i = 0; i < n; i++) {
                uint16_t c; memcpy(&c, p + pos + (size_t)i * 2, 2);
                out += (c < 128 ? (char)c : '?');
            }
        } else { ok = false; }
        size_t skip = (((size_t)(n + 1) * 2) + 3) & ~(size_t)3;
        pos += skip;
        if (pos > len) { ok = false; pos = len; }
        return out;
    }
    std::vector<int32_t> ints() {
        int32_t n = i32(); std::vector<int32_t> v;
        if (n < 0) return v;
        for (int32_t i = 0; i < n; i++) v.push_back(i32());
        return v;
    }
    std::vector<std::string> strs() {
        int32_t n = i32(); std::vector<std::string> v;
        if (n < 0) return v;
        for (int32_t i = 0; i < n; i++) v.push_back(str());
        return v;
    }
};

// ---------------------------------------------------------------------------
// RIL client: one long-lived connection to RilStub on /dev/socket/rild. Solicited
// replies are matched by serial; unsolicited frames go to a callback. RilStub
// switches to an identity-only safe mode after >5 connections in 60 s, so this
// connection is kept open and reconnects are spaced out.
// ---------------------------------------------------------------------------
namespace radio10 = android::hardware::radio::V1_0;

static void onUnsol(int32_t code, const uint8_t* data, size_t len);   // below, after the impl

class RilClient {
public:
    // Round trip one request. Returns false (err = RE_RADIO_NOT_AVAILABLE) when the
    // stub is unreachable; on success err is the stub's own error code.
    bool call(int32_t request, const std::vector<uint8_t>& args, int32_t serial,
              int32_t& err, std::vector<uint8_t>& payload) {
        std::unique_lock<std::mutex> lk(mMu);
        if (mFd < 0) { err = radio10::RE_RADIO_NOT_AVAILABLE; return false; }
        Slot slot;
        mPending[serial] = &slot;
        std::vector<uint8_t> frame;
        uint32_t plen = (uint32_t)(8 + args.size());
        frame.push_back((uint8_t)(plen >> 24)); frame.push_back((uint8_t)(plen >> 16));
        frame.push_back((uint8_t)(plen >> 8)); frame.push_back((uint8_t)plen);
        PWriter hdr; hdr.i32(request); hdr.i32(serial);
        frame.insert(frame.end(), hdr.b.begin(), hdr.b.end());
        frame.insert(frame.end(), args.begin(), args.end());
        bool wrote;
        {
            std::lock_guard<std::mutex> wlk(mWriteMu);
            wrote = writeAll(mFd, frame.data(), frame.size());
        }
        if (!wrote) {
            mPending.erase(serial);
            err = radio10::RE_RADIO_NOT_AVAILABLE;
            return false;
        }
        bool done = slot.cv.wait_for(lk, std::chrono::seconds(10), [&] { return slot.done; });
        mPending.erase(serial);
        if (!done) { err = radio10::RE_GENERIC_FAILURE; return false; }
        err = slot.err;
        payload = std::move(slot.payload);
        return true;
    }

    void run() {
        int attempt = 0;
        for (;;) {
            int fd = connectSocket();
            if (fd < 0) {
                if (attempt == 0 || attempt % 10 == 0)
                    LOG("ril: cannot connect /dev/socket/rild (errno=%d), retrying", errno);
                attempt++;
                std::this_thread::sleep_for(std::chrono::seconds(3));
                continue;
            }
            attempt = 0;
            {
                std::lock_guard<std::mutex> lk(mMu);
                mFd = fd;
            }
            LOG("ril: connected to RilStub");
            serve(fd);
            {
                std::lock_guard<std::mutex> lk(mMu);
                if (mFd == fd) mFd = -1;
                for (auto& kv : mPending) {
                    kv.second->err = radio10::RE_RADIO_NOT_AVAILABLE;
                    kv.second->done = true;
                    kv.second->cv.notify_all();
                }
            }
            close(fd);
            LOG("ril: connection lost, reconnecting");
            std::this_thread::sleep_for(std::chrono::seconds(3));
        }
    }

private:
    struct Slot {
        bool done = false; int32_t err = 0;
        std::vector<uint8_t> payload; std::condition_variable cv;
    };
    std::mutex mMu; std::mutex mWriteMu;
    int mFd = -1;
    std::unordered_map<int32_t, Slot*> mPending;

    static bool writeAll(int fd, const uint8_t* p, size_t n) {
        while (n > 0) {
            ssize_t w = write(fd, p, n);
            if (w < 0) { if (errno == EINTR) continue; return false; }
            p += w; n -= (size_t)w;
        }
        return true;
    }
    static bool readAll(int fd, uint8_t* p, size_t n) {
        while (n > 0) {
            ssize_t r = read(fd, p, n);
            if (r < 0) { if (errno == EINTR) continue; return false; }
            if (r == 0) return false;
            p += r; n -= (size_t)r;
        }
        return true;
    }
    static int connectSocket() {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) return -1;
        struct sockaddr_un un;
        memset(&un, 0, sizeof(un));
        un.sun_family = AF_UNIX;
        strncpy(un.sun_path, "/dev/socket/rild", sizeof(un.sun_path) - 1);
        if (connect(fd, (struct sockaddr*)&un, sizeof(un)) < 0) { close(fd); return -1; }
        return fd;
    }
    void serve(int fd) {
        std::vector<uint8_t> buf;
        for (;;) {
            uint8_t hdr[4];
            if (!readAll(fd, hdr, 4)) return;
            uint32_t len = ((uint32_t)hdr[0] << 24) | ((uint32_t)hdr[1] << 16) |
                           ((uint32_t)hdr[2] << 8) | (uint32_t)hdr[3];
            if (len < 8 || len > (1u << 20)) { LOG("ril: bad frame length %u", len); return; }
            buf.resize(len);
            if (!readAll(fd, buf.data(), len)) return;
            PReader r(buf.data(), len);
            int32_t type = r.i32();
            if (type == 0) {
                int32_t serial = r.i32();
                int32_t err = r.i32();
                std::lock_guard<std::mutex> lk(mMu);
                auto it = mPending.find(serial);
                if (it != mPending.end()) {
                    it->second->err = err;
                    it->second->payload.assign(buf.begin() + (ptrdiff_t)r.pos, buf.end());
                    it->second->done = true;
                    it->second->cv.notify_all();
                }
            } else if (type == 1) {
                int32_t code = r.i32();
                onUnsol(code, buf.data() + r.pos, len - r.pos);
            }
        }
    }
};

static RilClient g_ril;

// ---------------------------------------------------------------------------
// The IRadio implementation. Framework callbacks are held with the real
// incStrong/decStrong from libutils so the proxies outlive the binder frames
// that delivered them.
// ---------------------------------------------------------------------------
using android::hardware::Return;
using android::hardware::hidl_string;
using android::hardware::hidl_vec;
using android::hardware::hidl_array;
using android::hardware::hidl_handle;
using android::sp;
namespace r10 = android::hardware::radio::V1_0;
using Base = android::hidl::base::V1_0::IBase;
using DebugInfo = android::hidl::base::V1_0::DebugInfo;

static std::mutex g_cbMu;
static r10::IRadioResponse* g_resp = nullptr;
static r10::IRadioIndication* g_ind = nullptr;
static std::atomic<int32_t> g_radioState{-1};
static std::mutex g_opMu;
static std::string g_opLong, g_opShort, g_opNumeric;

static void holdObj(const void* p) { if (p && g_incStrong) g_incStrong(p, p); }
static void dropObj(const void* p) { if (p && g_decStrong) g_decStrong(p, p); }

static r10::IRadioResponse* resp() {
    std::lock_guard<std::mutex> lk(g_cbMu);
    return g_resp;
}
static r10::RadioResponseInfo mkInfo(int32_t serial, int32_t err) {
    return r10::RadioResponseInfo{r10::RESP_SOLICITED, serial, err};
}
static hidl_string hs(const std::string& s) { return hidl_string(s); }

struct RilReply { bool ok = false; int32_t err = 0; std::vector<uint8_t> data; };
static RilReply ril(int32_t req, const PWriter& args, int32_t serial) {
    RilReply r;
    r.ok = g_ril.call(req, args.b, serial, r.err, r.data);
    return r;
}
static RilReply ril(int32_t req, int32_t serial) { PWriter w; return ril(req, w, serial); }

// Legacy RIL request codes, shared with RilStub.
enum {
    Q_SIM_STATUS = 1, Q_CURRENT_CALLS = 9, Q_DIAL = 10, Q_IMSI = 11,
    Q_SIGNAL = 19, Q_VOICE_REG = 20, Q_DATA_REG = 21, Q_OPERATOR = 22,
    Q_POWER = 23, Q_SIM_IO = 28, Q_FACILITY = 42, Q_NET_SEL_MODE = 45,
    Q_BASEBAND = 51, Q_PREF_NET = 74, Q_IDENTITY = 98, Q_SMSC = 100, Q_VOICE_TECH = 108
};

// Build the cell identity from a registration reply: strs[1]/strs[2] are the LAC/CID
// in hex, and the MCC/MNC split comes from the cached operator numeric.
static void fillCell(r10::CellIdentity& ci, const std::vector<std::string>& strs,
                     std::string& mccBuf, std::string& mncBuf, r10::CellIdentityGsm& gsmBuf) {
    std::string numeric;
    { std::lock_guard<std::mutex> lk(g_opMu); numeric = g_opNumeric; }
    if (numeric.size() < 5 || strs.size() < 3) return;
    mccBuf = numeric.substr(0, 3);
    mncBuf = numeric.substr(3);
    gsmBuf = r10::CellIdentityGsm{};
    gsmBuf.mcc = hs(mccBuf);
    gsmBuf.mnc = hs(mncBuf);
    gsmBuf.lac = (int32_t)strtol(strs[1].c_str(), nullptr, 16);
    gsmBuf.cid = (int32_t)strtol(strs[2].c_str(), nullptr, 16);
    gsmBuf.arfcn = 0;
    gsmBuf.bsic = 0xFF;
    ci.cellInfoType = 1;   // GSM
    ci.cellIdentityGsm = hidl_vec<r10::CellIdentityGsm>(&gsmBuf, 1);
}

struct RadioImpl : public android::hardware::radio::V1_4::IRadio {
    // ---- IBase ----
    Return<void> interfaceChain(
        std::function<void(const hidl_vec<hidl_string>&)> cb) override {
        static const char* kChain[] = {
            "android.hardware.radio@1.4::IRadio", "android.hardware.radio@1.3::IRadio",
            "android.hardware.radio@1.2::IRadio", "android.hardware.radio@1.1::IRadio",
            "android.hardware.radio@1.0::IRadio", "android.hidl.base@1.0::IBase"};
        hidl_string arr[6];
        for (int i = 0; i < 6; i++) arr[i] = hidl_string(kChain[i]);
        cb(hidl_vec<hidl_string>(arr, 6));
        return Return<void>();
    }
    Return<void> debug(const hidl_handle& fd, const hidl_vec<hidl_string>&) override {
        if (fd.handle) {
            const int32_t* h = (const int32_t*)fd.handle;
            if (h[1] >= 1) {
                const char* msg = "aemu-radio IRadio slot1\n";
                ssize_t unused = write(h[3], msg, strlen(msg));
                (void)unused;
            }
        }
        return Return<void>();
    }
    Return<void> interfaceDescriptor(std::function<void(const hidl_string&)> cb) override {
        cb(hidl_string("android.hardware.radio@1.4::IRadio"));
        return Return<void>();
    }
    Return<void> getHashChain(
        std::function<void(const hidl_vec<hidl_array<uint8_t, 32>>&)> cb) override {
        hidl_array<uint8_t, 32> zeros[6];
        memset(zeros, 0, sizeof(zeros));
        cb(hidl_vec<hidl_array<uint8_t, 32>>(zeros, 6));
        return Return<void>();
    }
    Return<void> setHALInstrumentation() override { return Return<void>(); }
    Return<bool> linkToDeath(const sp<android::hardware::hidl_death_recipient>& recipient,
                             uint64_t cookie) override {
        holdObj(recipient.mPtr);
        std::lock_guard<std::mutex> lk(mDeathMu);
        dropObj(mDeathRecipient);
        mDeathRecipient = recipient.mPtr;
        mDeathCookie = cookie;
        return Return<bool>(true);
    }
    Return<void> ping() override { return Return<void>(); }
    Return<void> getDebugInfo(std::function<void(const DebugInfo&)> cb) override {
        DebugInfo info{};
        info.pid = (int32_t)getpid();
        info.ptr = (uint64_t)(uintptr_t)this;
        info.arch = 1;
        cb(info);
        return Return<void>();
    }
    Return<void> notifySyspropsChanged() override { return Return<void>(); }
    Return<bool> unlinkToDeath(const sp<android::hardware::hidl_death_recipient>&) override {
        std::lock_guard<std::mutex> lk(mDeathMu);
        dropObj(mDeathRecipient);
        mDeathRecipient = nullptr;
        return Return<bool>(true);
    }

    // ---- setResponseFunctions ----
    Return<void> setResponseFunctions(const sp<r10::IRadioResponse>& radioResponse,
                                      const sp<r10::IRadioIndication>& radioIndication) override {
        r10::IRadioResponse* newResp = radioResponse.mPtr;
        r10::IRadioIndication* newInd = radioIndication.mPtr;
        holdObj(newResp);
        holdObj(newInd);
        r10::IRadioResponse* oldResp;
        r10::IRadioIndication* oldInd;
        {
            std::lock_guard<std::mutex> lk(g_cbMu);
            oldResp = g_resp; oldInd = g_ind;
            g_resp = newResp; g_ind = newInd;
        }
        dropObj(oldResp);
        dropObj(oldInd);
        LOG("setResponseFunctions: resp=%p ind=%p", (void*)newResp, (void*)newInd);
        if (newResp) {
            newResp->interfaceChain([](const hidl_vec<hidl_string>& chain) {
                for (uint32_t i = 0; i < chain.size; i++) {
                    const hidl_string& s = chain.buffer[i];
                    LOG("  callback chain[%u]: %.*s", i, (int)s.length, s.buffer ? s.buffer : "");
                }
            });
        }
        if (g_radioState.load() == r10::RADIO_ON && newInd) {
            newInd->radioStateChanged(r10::IND_UNSOLICITED, r10::RADIO_ON);
            newInd->simStatusChanged(r10::IND_UNSOLICITED);
            newInd->networkStateChanged(r10::IND_UNSOLICITED);
        }
        return Return<void>();
    }

    // ---- SIM / identity (the point of this service) ----
    Return<void> getIccCardStatus(int32_t serial) override {
        auto rep = ril(Q_SIM_STATUS, serial);
        PReader pr(rep.data.data(), rep.data.size());
        r10::CardStatus cs{};
        std::string aid, label;
        r10::AppStatus app{};
        std::vector<r10::AppStatus> apps;
        if (rep.ok) {
            cs.cardState = pr.i32();
            cs.universalPinState = pr.i32();
            cs.gsmUmtsSubscriptionAppIndex = pr.i32();
            cs.cdmaSubscriptionAppIndex = pr.i32();
            cs.imsSubscriptionAppIndex = pr.i32();
            int32_t count = pr.i32();
            if (count > 0) {
                app.appType = pr.i32();
                app.appState = pr.i32();
                app.persoSubstate = pr.i32();
                aid = pr.str();
                label = pr.str();
                app.aidPtr = hs(aid);
                app.appLabelPtr = hs(label);
                app.pin1Replaced = pr.i32();
                app.pin1 = pr.i32();
                app.pin2 = pr.i32();
                apps.push_back(app);
                cs.applications = hidl_vec<r10::AppStatus>(apps.data(), (uint32_t)apps.size());
            }
        }
        LOG("getIccCardStatus(%d): err=%d cardState=%d apps=%zu", serial, rep.err, cs.cardState, apps.size());
        if (auto* r = resp()) r->getIccCardStatusResponse(mkInfo(serial, rep.ok ? rep.err : r10::RE_RADIO_NOT_AVAILABLE), cs);
        return Return<void>();
    }
    Return<void> getImsiForApp(int32_t serial, const hidl_string& aid) override {
        PWriter w;
        w.str(aid.buffer ? std::string(aid.buffer, aid.length) : std::string());
        auto rep = ril(Q_IMSI, w, serial);
        PReader pr(rep.data.data(), rep.data.size());
        std::string imsi = rep.ok && rep.err == 0 ? pr.str() : std::string();
        LOG("getImsiForApp(%d): err=%d imsi=%s", serial, rep.err, imsi.c_str());
        if (auto* r = resp()) r->getIMSIForAppResponse(mkInfo(serial, rep.err), hs(imsi));
        return Return<void>();
    }
    Return<void> iccIOForApp(int32_t serial, const r10::IccIo& io) override {
        PWriter w;
        w.i32(io.command);
        w.i32(io.fileId);
        w.str(io.path.buffer ? std::string(io.path.buffer, io.path.length) : std::string());
        w.i32(io.p1);
        w.i32(io.p2);
        w.i32(io.p3);
        w.str(io.data.buffer ? std::string(io.data.buffer, io.data.length) : std::string());
        w.str(io.pin2.buffer ? std::string(io.pin2.buffer, io.pin2.length) : std::string());
        w.str(io.aid.buffer ? std::string(io.aid.buffer, io.aid.length) : std::string());
        auto rep = ril(Q_SIM_IO, w, serial);
        PReader pr(rep.data.data(), rep.data.size());
        r10::IccIoResult out{};
        std::string simResp;
        if (rep.ok && rep.err == 0) {
            out.sw1 = pr.i32();
            out.sw2 = pr.i32();
            simResp = pr.str();
            out.simResponse = hs(simResp);
        }
        LOG("iccIOForApp(%d): cmd=%d file=0x%x err=%d sw=%02x/%02x", serial, io.command, io.fileId, rep.err, out.sw1, out.sw2);
        if (auto* r = resp()) r->iccIOForAppResponse(mkInfo(serial, rep.err), out);
        return Return<void>();
    }
    Return<void> getOperator(int32_t serial) override {
        auto rep = ril(Q_OPERATOR, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto strs = rep.ok && rep.err == 0 ? pr.strs() : std::vector<std::string>();
        std::string lo = strs.size() > 0 ? strs[0] : std::string();
        std::string sh = strs.size() > 1 ? strs[1] : std::string();
        std::string nu = strs.size() > 2 ? strs[2] : std::string();
        {
            std::lock_guard<std::mutex> lk(g_opMu);
            g_opLong = lo; g_opShort = sh; g_opNumeric = nu;
        }
        LOG("getOperator(%d): err=%d %s / %s", serial, rep.err, lo.c_str(), nu.c_str());
        if (auto* r = resp()) r->getOperatorResponse(mkInfo(serial, rep.err), hs(lo), hs(sh), hs(nu));
        return Return<void>();
    }
    Return<void> getBasebandVersion(int32_t serial) override {
        auto rep = ril(Q_BASEBAND, serial);
        PReader pr(rep.data.data(), rep.data.size());
        std::string v = rep.ok && rep.err == 0 ? pr.str() : std::string();
        if (auto* r = resp()) r->getBasebandVersionResponse(mkInfo(serial, rep.err), hs(v));
        return Return<void>();
    }
    Return<void> getDeviceIdentity(int32_t serial) override {
        auto rep = ril(Q_IDENTITY, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto strs = rep.ok && rep.err == 0 ? pr.strs() : std::vector<std::string>();
        auto at = [&](size_t i) { return strs.size() > i ? strs[i] : std::string(); };
        // The stub answers in legacy order [imei, imeisv, meid, ""];
        // the HIDL response order is (imei, imeisv, esn, meid).
        if (auto* r = resp())
            r->getDeviceIdentityResponse(mkInfo(serial, rep.err), hs(at(0)), hs(at(1)), hs(at(3)), hs(at(2)));
        return Return<void>();
    }
    Return<void> getSmscAddress(int32_t serial) override {
        auto rep = ril(Q_SMSC, serial);
        PReader pr(rep.data.data(), rep.data.size());
        std::string v = rep.ok && rep.err == 0 ? pr.str() : std::string();
        if (auto* r = resp()) r->getSmscAddressResponse(mkInfo(serial, rep.err), hs(v));
        return Return<void>();
    }

    // (class continues below)

    // ---- Registration, signal, power ----
    Return<void> getVoiceRegistrationState(int32_t serial) override {
        auto rep = ril(Q_VOICE_REG, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto strs = rep.ok && rep.err == 0 ? pr.strs() : std::vector<std::string>();
        r10::VoiceRegStateResult v{};
        v.regState = strs.size() > 0 ? atoi(strs[0].c_str()) : r10::REG_NOT_REG;
        v.rat = strs.size() > 3 ? atoi(strs[3].c_str()) : 0;
        v.cssSupported = false;
        v.roamingIndicator = -1;
        v.systemIsInPrl = -1;
        v.defaultRoamingIndicator = -1;
        v.reasonForDenial = 0;
        std::string mcc, mnc;
        r10::CellIdentityGsm gsm{};
        fillCell(v.cellIdentity, strs, mcc, mnc, gsm);
        LOG("getVoiceRegistrationState(%d): err=%d reg=%d rat=%d", serial, rep.err, v.regState, v.rat);
        if (auto* r = resp()) r->getVoiceRegistrationStateResponse(mkInfo(serial, rep.err), v);
        return Return<void>();
    }
    Return<void> getDataRegistrationState(int32_t serial) override {
        auto rep = ril(Q_DATA_REG, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto strs = rep.ok && rep.err == 0 ? pr.strs() : std::vector<std::string>();
        r10::DataRegStateResult v{};
        v.regState = strs.size() > 0 ? atoi(strs[0].c_str()) : r10::REG_NOT_REG;
        v.rat = strs.size() > 3 ? atoi(strs[3].c_str()) : 0;
        v.reasonDataDenied = strs.size() > 4 ? atoi(strs[4].c_str()) : 0;
        v.maxDataCalls = strs.size() > 5 ? atoi(strs[5].c_str()) : 0;
        std::string mcc, mnc;
        r10::CellIdentityGsm gsm{};
        fillCell(v.cellIdentity, strs, mcc, mnc, gsm);
        if (auto* r = resp()) r->getDataRegistrationStateResponse(mkInfo(serial, rep.err), v);
        return Return<void>();
    }
    Return<void> getSignalStrength(int32_t serial) override {
        if (auto* r = resp()) r->getSignalStrengthResponse(mkInfo(serial, 0), signalStrength(serial));
        return Return<void>();
    }
    Return<void> getSignalStrength_1_4(int32_t serial) override {
        // No 1.4 response object is ever delivered to a HAL (callbacks are 1.0);
        // answer through the 1.0 response, which carries the same serial.
        LOG("getSignalStrength_1_4(%d): answering via 1.0 response", serial);
        if (auto* r = resp()) r->getSignalStrengthResponse(mkInfo(serial, 0), signalStrength(serial));
        return Return<void>();
    }
    r10::SignalStrength signalStrength(int32_t serial) {
        auto rep = ril(Q_SIGNAL, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto v = rep.ok && rep.err == 0 ? pr.ints() : std::vector<int32_t>();
        r10::SignalStrength ss{};
        ss.gw.signalStrength = v.size() > 0 ? (uint32_t)v[0] : 20;
        ss.gw.bitErrorRate = v.size() > 1 ? (uint32_t)v[1] : 0;
        ss.gw.timingAdvance = INT32_MAX;
        ss.cdma.dbm = 0x7FFFFFFF; ss.cdma.ecio = 0x7FFFFFFF;
        ss.evdo.dbm = 0x7FFFFFFF; ss.evdo.ecio = 0x7FFFFFFF; ss.evdo.signalNoiseRatio = 0x7FFFFFFF;
        // The stub's legacy LTE slots are "unknown" markers; the guest is on a
        // strong synthetic cell, so report fixed plausible LTE values instead.
        ss.lte.signalStrength = 20; ss.lte.rsrp = 100; ss.lte.rsrq = 10;
        ss.lte.rssnr = 12; ss.lte.cqi = 10; ss.lte.timingAdvance = 0;
        ss.tdScdma.rscp = 0x7FFFFFFF;
        return ss;
    }
    Return<void> setRadioPower(int32_t serial, bool on) override {
        PWriter w;
        w.i32(1);
        w.i32(on ? 1 : 0);
        auto rep = ril(Q_POWER, w, serial);
        LOG("setRadioPower(%d): on=%d err=%d", serial, on ? 1 : 0, rep.err);
        if (auto* r = resp()) r->setRadioPowerResponse(mkInfo(serial, rep.err));
        return Return<void>();
    }
    Return<void> setSimCardPower_1_1(int32_t serial, bool powerUp) override {
        PWriter w;
        w.i32(1);
        w.i32(powerUp ? 1 : 0);
        auto rep = ril(Q_POWER, w, serial);
        LOG("setSimCardPower_1_1(%d): up=%d err=%d (answered via 1.0 response)", serial, powerUp ? 1 : 0, rep.err);
        if (auto* r = resp()) r->setSimCardPowerResponse(mkInfo(serial, rep.err));
        return Return<void>();
    }
    Return<void> setSimCardPower(int32_t serial) override {
        LOG("setSimCardPower(%d): 1.0 form, no state argument; ack only", serial);
        if (auto* r = resp()) r->setSimCardPowerResponse(mkInfo(serial, 0));
        return Return<void>();
    }

    // ---- Data calls: deliberately not served. The guest's data path is the
    // engine's own; a fake rmnet success here could hijack the default route. ----
    void setupDataCallFailed(int32_t serial) {
        r10::SetupDataCallResult d{};
        d.status = 0x1000;   // DataCallFailCause ERROR_UNSPECIFIED
        d.suggestedRetryTime = -1;
        d.cid = -1;
        d.active = 0;
        d.type = hs(std::string());
        d.ifname = hs(std::string());
        d.addresses = hs(std::string());
        d.dnses = hs(std::string());
        d.gateways = hs(std::string());
        d.pcscf = hs(std::string());
        d.mtu = 0;
        if (auto* r = resp()) {
            r->acknowledgeRequest(serial);
            r->setupDataCallResponse(mkInfo(serial, 0), d);
        }
    }
    Return<void> setupDataCall(int32_t serial) override { setupDataCallFailed(serial); return Return<void>(); }
    Return<void> setupDataCall_1_2(int32_t serial) override { setupDataCallFailed(serial); return Return<void>(); }
    Return<void> setupDataCall_1_4(int32_t serial) override { setupDataCallFailed(serial); return Return<void>(); }
    Return<void> getDataCallList(int32_t serial) override {
        if (auto* r = resp()) r->getDataCallListResponse(mkInfo(serial, 0), hidl_vec<r10::SetupDataCallResult>());
        return Return<void>();
    }
    Return<void> deactivateDataCall(int32_t serial) override {
        if (auto* r = resp()) r->deactivateDataCallResponse(mkInfo(serial, 0));
        return Return<void>();
    }
    Return<void> deactivateDataCall_1_2(int32_t serial) override {
        if (auto* r = resp()) r->deactivateDataCallResponse(mkInfo(serial, 0));
        return Return<void>();
    }

    // ---- Calls, misc queries with real answers from the stub ----
    Return<void> getCurrentCalls(int32_t serial) override {
        auto rep = ril(Q_CURRENT_CALLS, serial);
        PReader pr(rep.data.data(), rep.data.size());
        int32_t count = rep.ok && rep.err == 0 ? pr.i32() : 0;
        if (count > 0) LOG("getCurrentCalls(%d): stub reports %d calls; call audio is out of scope, answering empty", serial, count);
        if (auto* r = resp()) r->getCurrentCallsResponse(mkInfo(serial, rep.err), hidl_vec<r10::Call>());
        return Return<void>();
    }
    Return<void> dial(int32_t serial) override {
        auto rep = ril(Q_DIAL, serial);
        if (auto* r = resp()) r->dialResponse(mkInfo(serial, rep.err));
        return Return<void>();
    }
    Return<void> emergencyDial(int32_t serial) override {
        LOG("emergencyDial(%d): refused (no real network)", serial);
        if (auto* r = resp()) r->dialResponse(mkInfo(serial, r10::RE_GENERIC_FAILURE));
        return Return<void>();
    }
    Return<void> getLastCallFailCause(int32_t serial) override {
        r10::LastCallFailCauseInfo fc{};
        fc.causeCode = 16;   // NORMAL_CLEARING, as the stub answers
        fc.vendorCause = hs(std::string());
        if (auto* r = resp()) r->getLastCallFailCauseResponse(mkInfo(serial, 0), fc);
        return Return<void>();
    }
    Return<void> getFacilityLockForApp(int32_t serial) override {
        // Arguments (facility, password, serviceClass, aid) are not read; the stub
        // answers the lock state for the default facility, which is all a READY
        // synthetic SIM can meaningfully report.
        auto rep = ril(Q_FACILITY, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto v = rep.ok && rep.err == 0 ? pr.ints() : std::vector<int32_t>();
        if (auto* r = resp()) r->getFacilityLockForAppResponse(mkInfo(serial, rep.err), v.empty() ? 0 : v[0]);
        return Return<void>();
    }
    Return<void> getNetworkSelectionMode(int32_t serial) override {
        auto rep = ril(Q_NET_SEL_MODE, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto v = rep.ok && rep.err == 0 ? pr.ints() : std::vector<int32_t>();
        if (auto* r = resp()) r->getNetworkSelectionModeResponse(mkInfo(serial, rep.err), v.empty() ? 0 : v[0]);
        return Return<void>();
    }
    Return<void> getPreferredNetworkType(int32_t serial) override {
        auto rep = ril(Q_PREF_NET, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto v = rep.ok && rep.err == 0 ? pr.ints() : std::vector<int32_t>();
        if (auto* r = resp()) r->getPreferredNetworkTypeResponse(mkInfo(serial, rep.err), v.empty() ? 0 : v[0]);
        return Return<void>();
    }
    Return<void> getPreferredNetworkTypeBitmap(int32_t serial) override {
        // The 1.0 response carries the plain network type, which is the truthful
        // value here; a bitmap would need the 1.4 response object, which the
        // framework never hands a HAL (see setResponseFunctions).
        auto rep = ril(Q_PREF_NET, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto v = rep.ok && rep.err == 0 ? pr.ints() : std::vector<int32_t>();
        LOG("getPreferredNetworkTypeBitmap(%d): answering via 1.0 getPreferredNetworkTypeResponse", serial);
        if (auto* r = resp()) r->getPreferredNetworkTypeResponse(mkInfo(serial, rep.err), v.empty() ? 0 : v[0]);
        return Return<void>();
    }
    Return<void> getVoiceRadioTechnology(int32_t serial) override {
        auto rep = ril(Q_VOICE_TECH, serial);
        PReader pr(rep.data.data(), rep.data.size());
        auto v = rep.ok && rep.err == 0 ? pr.ints() : std::vector<int32_t>();
        if (auto* r = resp()) r->getVoiceRadioTechnologyResponse(mkInfo(serial, rep.err), v.empty() ? 0 : v[0]);
        return Return<void>();
    }
    Return<void> getAvailableNetworks(int32_t serial) override {
        std::string lo, sh, nu;
        {
            std::lock_guard<std::mutex> lk(g_opMu);
            lo = g_opLong; sh = g_opShort; nu = g_opNumeric;
        }
        if (nu.empty()) {
            auto rep = ril(Q_OPERATOR, serial);
            PReader pr(rep.data.data(), rep.data.size());
            auto strs = rep.ok && rep.err == 0 ? pr.strs() : std::vector<std::string>();
            if (strs.size() > 2) {
                lo = strs[0]; sh = strs[1]; nu = strs[2];
                std::lock_guard<std::mutex> lk(g_opMu);
                g_opLong = lo; g_opShort = sh; g_opNumeric = nu;
            }
        }
        r10::OperatorInfo oi{};
        oi.alphaLong = hs(lo);
        oi.alphaShort = hs(sh);
        oi.operatorNumeric = hs(nu);
        oi.status = r10::OP_CURRENT;
        hidl_vec<r10::OperatorInfo> vec;
        if (!nu.empty()) vec = hidl_vec<r10::OperatorInfo>(&oi, 1);
        if (auto* r = resp()) r->getAvailableNetworksResponse(mkInfo(serial, 0), vec);
        return Return<void>();
    }
    Return<void> getRadioCapability(int32_t serial) override {
        if (auto* r = resp()) r->getRadioCapabilityResponse(mkInfo(serial, 0), radioCapability());
        return Return<void>();
    }
    Return<void> setRadioCapability(int32_t serial) override {
        if (auto* r = resp()) r->setRadioCapabilityResponse(mkInfo(serial, 0), radioCapability());
        return Return<void>();
    }
    r10::RadioCapability radioCapability() {
        r10::RadioCapability rc{};
        rc.session = 0;
        rc.phase = 0;          // CONFIGURED
        rc.raf = 0xE707;       // GSM family + LTE (see RAF bits in types.hal)
        rc.logicalModemUuid = hidl_string("com.aemu.lm0");
        rc.status = 1;         // SUCCESS
        return rc;
    }
    // (class continues below)

    // ---- Everything below is answered locally: either the operation is a no-op
    // on a synthetic SIM, or it is refused with a real error code. ----
    void infoOnly(int32_t serial, int32_t err,
                  Return<void> (r10::IRadioResponse::*fn)(const r10::RadioResponseInfo&)) {
        if (auto* r = resp()) (r->*fn)(mkInfo(serial, err));
    }
    Return<void> supplyIccPinForApp(int32_t serial) override { if (auto* r = resp()) r->supplyIccPinForAppResponse(mkInfo(serial, 0), -1); return Return<void>(); }
    Return<void> supplyIccPukForApp(int32_t serial) override { if (auto* r = resp()) r->supplyIccPukForAppResponse(mkInfo(serial, 0), -1); return Return<void>(); }
    Return<void> supplyIccPin2ForApp(int32_t serial) override { if (auto* r = resp()) r->supplyIccPin2ForAppResponse(mkInfo(serial, 0), -1); return Return<void>(); }
    Return<void> supplyIccPuk2ForApp(int32_t serial) override { if (auto* r = resp()) r->supplyIccPuk2ForAppResponse(mkInfo(serial, 0), -1); return Return<void>(); }
    Return<void> changeIccPinForApp(int32_t serial) override { if (auto* r = resp()) r->changeIccPinForAppResponse(mkInfo(serial, 0), -1); return Return<void>(); }
    Return<void> changeIccPin2ForApp(int32_t serial) override { if (auto* r = resp()) r->changeIccPin2ForAppResponse(mkInfo(serial, 0), -1); return Return<void>(); }
    Return<void> supplyNetworkDepersonalization(int32_t serial) override { if (auto* r = resp()) r->supplyNetworkDepersonalizationResponse(mkInfo(serial, 0), -1); return Return<void>(); }
    Return<void> hangup(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::hangupConnectionResponse); return Return<void>(); }
    Return<void> hangupWaitingOrBackground(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::hangupWaitingOrBackgroundResponse); return Return<void>(); }
    Return<void> hangupForegroundResumeBackground(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::hangupForegroundResumeBackgroundResponse); return Return<void>(); }
    Return<void> switchWaitingOrHoldingAndActive(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::switchWaitingOrHoldingAndActiveResponse); return Return<void>(); }
    Return<void> conference(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::conferenceResponse); return Return<void>(); }
    Return<void> rejectCall(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::rejectCallResponse); return Return<void>(); }
    Return<void> sendDtmf(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::sendDtmfResponse); return Return<void>(); }
    Return<void> startDtmf(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::startDtmfResponse); return Return<void>(); }
    Return<void> stopDtmf(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::stopDtmfResponse); return Return<void>(); }
    Return<void> sendSms(int32_t serial) override { smsRefused(serial, &r10::IRadioResponse::sendSmsResponse); return Return<void>(); }
    Return<void> sendSMSExpectMore(int32_t serial) override { smsRefused(serial, &r10::IRadioResponse::sendSMSExpectMoreResponse); return Return<void>(); }
    Return<void> sendCdmaSms(int32_t serial) override { smsRefused(serial, &r10::IRadioResponse::sendCdmaSmsResponse); return Return<void>(); }
    Return<void> sendImsSms(int32_t serial) override { smsRefused(serial, &r10::IRadioResponse::sendImsSmsResponse); return Return<void>(); }
    void smsRefused(int32_t serial,
                    Return<void> (r10::IRadioResponse::*fn)(const r10::RadioResponseInfo&, const r10::SendSmsResult&)) {
        r10::SendSmsResult s{};
        s.ackPDU = hs(std::string());
        if (auto* r = resp()) (r->*fn)(mkInfo(serial, r10::RE_REQUEST_NOT_SUPPORTED), s);
    }
    Return<void> sendUssd(int32_t serial) override { infoOnly(serial, r10::RE_REQUEST_NOT_SUPPORTED, &r10::IRadioResponse::sendUssdResponse); return Return<void>(); }
    Return<void> cancelPendingUssd(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::cancelPendingUssdResponse); return Return<void>(); }
    Return<void> getClir(int32_t serial) override { if (auto* r = resp()) r->getClirResponse(mkInfo(serial, 0), 0, 2); return Return<void>(); }
    Return<void> setClir(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setClirResponse); return Return<void>(); }
    Return<void> getCallForwardStatus(int32_t serial) override { if (auto* r = resp()) r->getCallForwardStatusResponse(mkInfo(serial, 0), hidl_vec<r10::CallForwardInfo>()); return Return<void>(); }
    Return<void> setCallForward(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setCallForwardResponse); return Return<void>(); }
    Return<void> getCallWaiting(int32_t serial) override { if (auto* r = resp()) r->getCallWaitingResponse(mkInfo(serial, 0), false, 0); return Return<void>(); }
    Return<void> setCallWaiting(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setCallWaitingResponse); return Return<void>(); }
    Return<void> acknowledgeLastIncomingGsmSms(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::acknowledgeLastIncomingGsmSmsResponse); return Return<void>(); }
    Return<void> acceptCall(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::acceptCallResponse); return Return<void>(); }
    Return<void> setFacilityLockForApp(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setFacilityLockForAppResponse); return Return<void>(); }
    Return<void> setBarringPassword(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setBarringPasswordResponse); return Return<void>(); }
    Return<void> setNetworkSelectionModeAutomatic(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setNetworkSelectionModeAutomaticResponse); return Return<void>(); }
    Return<void> setNetworkSelectionModeManual(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setNetworkSelectionModeManualResponse); return Return<void>(); }
    Return<void> separateConnection(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::separateConnectionResponse); return Return<void>(); }
    Return<void> setMute(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setMuteResponse); return Return<void>(); }
    Return<void> getMute(int32_t serial) override { if (auto* r = resp()) r->getMuteResponse(mkInfo(serial, 0), false); return Return<void>(); }
    Return<void> getClip(int32_t serial) override { if (auto* r = resp()) r->getClipResponse(mkInfo(serial, 0), 0); return Return<void>(); }
    Return<void> setSuppServiceNotifications(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setSuppServiceNotificationsResponse); return Return<void>(); }
    Return<void> writeSmsToSim(int32_t serial) override { if (auto* r = resp()) r->writeSmsToSimResponse(mkInfo(serial, r10::RE_REQUEST_NOT_SUPPORTED), 0); return Return<void>(); }
    Return<void> deleteSmsOnSim(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::deleteSmsOnSimResponse); return Return<void>(); }
    Return<void> setBandMode(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setBandModeResponse); return Return<void>(); }
    Return<void> getAvailableBandModes(int32_t serial) override { if (auto* r = resp()) r->getAvailableBandModesResponse(mkInfo(serial, 0), hidl_vec<int32_t>()); return Return<void>(); }
    Return<void> sendEnvelope(int32_t serial) override { iccOk(serial, &r10::IRadioResponse::sendEnvelopeResponse); return Return<void>(); }
    Return<void> sendTerminalResponseToSim(int32_t serial) override { iccOk(serial, &r10::IRadioResponse::sendTerminalResponseToSimResponse); return Return<void>(); }
    Return<void> sendEnvelopeWithStatus(int32_t serial) override { iccOk(serial, &r10::IRadioResponse::sendEnvelopeWithStatusResponse); return Return<void>(); }
    void iccOk(int32_t serial,
               Return<void> (r10::IRadioResponse::*fn)(const r10::RadioResponseInfo&, const r10::IccIoResult&)) {
        r10::IccIoResult out{};
        out.sw1 = 0x90; out.sw2 = 0x00;
        out.simResponse = hs(std::string());
        if (auto* r = resp()) (r->*fn)(mkInfo(serial, 0), out);
    }
    void iccRefused(int32_t serial,
                    Return<void> (r10::IRadioResponse::*fn)(const r10::RadioResponseInfo&, const r10::IccIoResult&)) {
        // Same policy as the 6.0 guest, where logical-channel requests fail:
        // authentication over a synthetic SIM is refused, never faked.
        r10::IccIoResult out{};
        out.simResponse = hs(std::string());
        if (auto* r = resp()) (r->*fn)(mkInfo(serial, r10::RE_GENERIC_FAILURE), out);
    }
    Return<void> handleStkCallSetupRequestFromSim(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::handleStkCallSetupRequestFromSimResponse); return Return<void>(); }
    Return<void> explicitCallTransfer(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::explicitCallTransferResponse); return Return<void>(); }
    Return<void> setPreferredNetworkType(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setPreferredNetworkTypeResponse); return Return<void>(); }
    Return<void> setPreferredNetworkTypeBitmap(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setPreferredNetworkTypeResponse); return Return<void>(); }
    Return<void> getNeighboringCids(int32_t serial) override { if (auto* r = resp()) r->getNeighboringCidsResponse(mkInfo(serial, 0), hidl_vec<r10::NeighboringCell>()); return Return<void>(); }
    Return<void> setLocationUpdates(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setLocationUpdatesResponse); return Return<void>(); }
    Return<void> setCdmaSubscriptionSource(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setCdmaSubscriptionSourceResponse); return Return<void>(); }
    Return<void> setCdmaRoamingPreference(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setCdmaRoamingPreferenceResponse); return Return<void>(); }
    Return<void> getCdmaRoamingPreference(int32_t serial) override { if (auto* r = resp()) r->getCdmaRoamingPreferenceResponse(mkInfo(serial, 0), 0); return Return<void>(); }
    Return<void> setTTYMode(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setTTYModeResponse); return Return<void>(); }
    Return<void> getTTYMode(int32_t serial) override { if (auto* r = resp()) r->getTTYModeResponse(mkInfo(serial, 0), 0); return Return<void>(); }
    Return<void> setPreferredVoicePrivacy(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setPreferredVoicePrivacyResponse); return Return<void>(); }
    Return<void> getPreferredVoicePrivacy(int32_t serial) override { if (auto* r = resp()) r->getPreferredVoicePrivacyResponse(mkInfo(serial, 0), false); return Return<void>(); }
    Return<void> sendCDMAFeatureCode(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::sendCDMAFeatureCodeResponse); return Return<void>(); }
    Return<void> sendBurstDtmf(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::sendBurstDtmfResponse); return Return<void>(); }
    Return<void> acknowledgeLastIncomingCdmaSms(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::acknowledgeLastIncomingCdmaSmsResponse); return Return<void>(); }
    Return<void> getGsmBroadcastConfig(int32_t serial) override { if (auto* r = resp()) r->getGsmBroadcastConfigResponse(mkInfo(serial, 0), hidl_vec<r10::GsmBroadcastSmsConfigInfo>()); return Return<void>(); }
    Return<void> setGsmBroadcastConfig(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setGsmBroadcastConfigResponse); return Return<void>(); }
    Return<void> setGsmBroadcastActivation(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setGsmBroadcastActivationResponse); return Return<void>(); }
    Return<void> getCdmaBroadcastConfig(int32_t serial) override { if (auto* r = resp()) r->getCdmaBroadcastConfigResponse(mkInfo(serial, 0), hidl_vec<r10::CdmaBroadcastSmsConfigInfo>()); return Return<void>(); }
    Return<void> setCdmaBroadcastConfig(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setCdmaBroadcastConfigResponse); return Return<void>(); }
    Return<void> setCdmaBroadcastActivation(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setCdmaBroadcastActivationResponse); return Return<void>(); }
    Return<void> getCDMASubscription(int32_t serial) override {
        std::string e;
        if (auto* r = resp()) r->getCDMASubscriptionResponse(mkInfo(serial, 0), hs(e), hs(e), hs(e), hs(e), hs(e));
        return Return<void>();
    }
    Return<void> writeSmsToRuim(int32_t serial) override { if (auto* r = resp()) r->writeSmsToRuimResponse(mkInfo(serial, r10::RE_REQUEST_NOT_SUPPORTED), 0); return Return<void>(); }
    Return<void> deleteSmsOnRuim(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::deleteSmsOnRuimResponse); return Return<void>(); }
    Return<void> exitEmergencyCallbackMode(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::exitEmergencyCallbackModeResponse); return Return<void>(); }
    Return<void> setSmscAddress(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setSmscAddressResponse); return Return<void>(); }
    Return<void> reportSmsMemoryStatus(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::reportSmsMemoryStatusResponse); return Return<void>(); }
    Return<void> reportStkServiceIsRunning(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::reportStkServiceIsRunningResponse); return Return<void>(); }
    Return<void> getCdmaSubscriptionSource(int32_t serial) override { if (auto* r = resp()) r->getCdmaSubscriptionSourceResponse(mkInfo(serial, 0), 0); return Return<void>(); }
    Return<void> requestIsimAuthentication(int32_t serial) override { iccRefused(serial, &r10::IRadioResponse::requestIsimAuthenticationResponse); return Return<void>(); }
    Return<void> requestIccSimAuthentication(int32_t serial) override { iccRefused(serial, &r10::IRadioResponse::requestIccSimAuthenticationResponse); return Return<void>(); }
    Return<void> acknowledgeIncomingGsmSmsWithPdu(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::acknowledgeIncomingGsmSmsWithPduResponse); return Return<void>(); }
    Return<void> getCellInfoList(int32_t serial) override { if (auto* r = resp()) r->getCellInfoListResponse(mkInfo(serial, 0), hidl_vec<r10::CellInfo>()); return Return<void>(); }
    Return<void> setCellInfoListRate(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setCellInfoListRateResponse); return Return<void>(); }
    Return<void> setInitialAttachApn(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setInitialAttachApnResponse); return Return<void>(); }
    Return<void> setInitialAttachApn_1_4(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setInitialAttachApnResponse); return Return<void>(); }
    Return<void> getImsRegistrationState(int32_t serial) override { if (auto* r = resp()) r->getImsRegistrationStateResponse(mkInfo(serial, 0), false, 0); return Return<void>(); }
    Return<void> iccTransmitApduBasicChannel(int32_t serial) override { iccRefused(serial, &r10::IRadioResponse::iccTransmitApduBasicChannelResponse); return Return<void>(); }
    Return<void> iccTransmitApduLogicalChannel(int32_t serial) override { iccRefused(serial, &r10::IRadioResponse::iccTransmitApduLogicalChannelResponse); return Return<void>(); }
    Return<void> iccOpenLogicalChannel(int32_t serial) override {
        r10::IccOpenLogicalChannelResponse out{};
        out.selectResponse = hs(std::string());
        if (auto* r = resp()) r->iccOpenLogicalChannelResponse(mkInfo(serial, r10::RE_GENERIC_FAILURE), out);
        return Return<void>();
    }
    Return<void> iccCloseLogicalChannel(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::iccCloseLogicalChannelResponse); return Return<void>(); }
    Return<void> nvReadItem(int32_t serial) override { if (auto* r = resp()) r->nvReadItemResponse(mkInfo(serial, 0), hs(std::string())); return Return<void>(); }
    Return<void> nvWriteItem(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::nvWriteItemResponse); return Return<void>(); }
    Return<void> nvWriteCdmaPrl(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::nvWriteCdmaPrlResponse); return Return<void>(); }
    Return<void> nvResetConfig(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::nvResetConfigResponse); return Return<void>(); }
    Return<void> setUiccSubscription(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setUiccSubscriptionResponse); return Return<void>(); }
    Return<void> setDataAllowed(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setDataAllowedResponse); return Return<void>(); }
    Return<void> getHardwareConfig(int32_t serial) override { if (auto* r = resp()) r->getHardwareConfigResponse(mkInfo(serial, 0), hidl_vec<r10::HardwareConfig>()); return Return<void>(); }
    Return<void> setDataProfile(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setDataProfileResponse); return Return<void>(); }
    Return<void> setDataProfile_1_4(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setDataProfileResponse); return Return<void>(); }
    Return<void> requestShutdown(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::requestShutdownResponse); return Return<void>(); }
    Return<void> startLceService(int32_t serial) override { lceRefused(serial, &r10::IRadioResponse::startLceServiceResponse); return Return<void>(); }
    Return<void> stopLceService(int32_t serial) override { lceRefused(serial, &r10::IRadioResponse::stopLceServiceResponse); return Return<void>(); }
    void lceRefused(int32_t serial,
                    Return<void> (r10::IRadioResponse::*fn)(const r10::RadioResponseInfo&, const r10::LceStatusInfo&)) {
        r10::LceStatusInfo s{};
        if (auto* r = resp()) (r->*fn)(mkInfo(serial, r10::RE_REQUEST_NOT_SUPPORTED), s);
    }
    Return<void> pullLceData(int32_t serial) override {
        r10::LceDataInfo d{};
        if (auto* r = resp()) r->pullLceDataResponse(mkInfo(serial, r10::RE_REQUEST_NOT_SUPPORTED), d);
        return Return<void>();
    }
    Return<void> getModemActivityInfo(int32_t serial) override {
        r10::ActivityStatsInfo a{};
        if (auto* r = resp()) r->getModemActivityInfoResponse(mkInfo(serial, 0), a);
        return Return<void>();
    }
    Return<void> setAllowedCarriers(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setAllowedCarriersResponse); return Return<void>(); }
    Return<void> setAllowedCarriers_1_4(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setAllowedCarriersResponse); return Return<void>(); }
    Return<void> getAllowedCarriers(int32_t serial) override { allowedCarriers(serial); return Return<void>(); }
    Return<void> getAllowedCarriers_1_4(int32_t serial) override { allowedCarriers(serial); return Return<void>(); }
    void allowedCarriers(int32_t serial) {
        r10::CarrierRestrictions cr{};
        if (auto* r = resp()) r->getAllowedCarriersResponse(mkInfo(serial, 0), true, cr);
    }
    Return<void> sendDeviceState(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::sendDeviceStateResponse); return Return<void>(); }
    Return<void> setIndicationFilter(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setIndicationFilterResponse); return Return<void>(); }
    Return<void> setIndicationFilter_1_2(int32_t serial) override { infoOnly(serial, 0, &r10::IRadioResponse::setIndicationFilterResponse); return Return<void>(); }
    Return<void> responseAcknowledgement(int32_t serial) override {
        LOG("responseAcknowledgement(%d): no ack-expecting indications are sent; ignored", serial);
        return Return<void>();
    }

    // ---- 1.1–1.4 requests with no 1.0 response: log only. None of them is on
    // the bring-up path; the framework times them out on its own. ----
    Return<void> setCarrierInfoForImsiEncryption(int32_t serial) override { LOG("setCarrierInfoForImsiEncryption(%d): no 1.0 response exists; ignored", serial); return Return<void>(); }
    Return<void> startNetworkScan(int32_t serial) override { scanIgnored(serial, "startNetworkScan"); return Return<void>(); }
    Return<void> stopNetworkScan(int32_t serial) override { scanIgnored(serial, "stopNetworkScan"); return Return<void>(); }
    Return<void> startNetworkScan_1_2(int32_t serial) override { scanIgnored(serial, "startNetworkScan_1_2"); return Return<void>(); }
    Return<void> startNetworkScan_1_4(int32_t serial) override { scanIgnored(serial, "startNetworkScan_1_4"); return Return<void>(); }
    void scanIgnored(int32_t serial, const char* what) {
        if (auto* r = resp()) r->acknowledgeRequest(serial);
        LOG("%s(%d): network scan is out of scope; acknowledged, no result", what, serial);
    }
    Return<void> startKeepalive(int32_t serial) override { LOG("startKeepalive(%d): telephony keepalive not served (data path is the engine's); ignored", serial); return Return<void>(); }
    Return<void> stopKeepalive(int32_t serial) override { LOG("stopKeepalive(%d): ignored", serial); return Return<void>(); }
    Return<void> setSignalStrengthReportingCriteria(int32_t serial) override { LOG("setSignalStrengthReportingCriteria(%d): ignored (static signal)", serial); return Return<void>(); }
    Return<void> setLinkCapacityReportingCriteria(int32_t serial) override { LOG("setLinkCapacityReportingCriteria(%d): ignored", serial); return Return<void>(); }
    Return<void> setSystemSelectionChannels(int32_t serial) override { LOG("setSystemSelectionChannels(%d): ignored", serial); return Return<void>(); }
    Return<void> enableModem(int32_t serial) override { LOG("enableModem(%d): ignored (single always-on modem)", serial); return Return<void>(); }
    Return<void> getModemStackStatus(int32_t serial) override { LOG("getModemStackStatus(%d): no 1.0 response exists; ignored", serial); return Return<void>(); }

private:
    std::mutex mDeathMu;
    android::hardware::hidl_death_recipient* mDeathRecipient = nullptr;
    uint64_t mDeathCookie = 0;
};

// ---------------------------------------------------------------------------
// Unsolicited frames from the stub, translated to HIDL indications. Codes are
// the stub's own (see Stubs.kt): 1000 radio state, 1001 call state, 1002 voice
// network state, 1003 new SMS, 1018 call ring, 1019 SIM status, 1034 connected.
// ---------------------------------------------------------------------------
static std::vector<uint8_t> hexDecode(const std::string& hex) {
    auto nib = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    std::vector<uint8_t> out;
    for (size_t i = 0; i + 1 < hex.size(); i += 2) {
        int hi = nib(hex[i]), lo = nib(hex[i + 1]);
        if (hi < 0 || lo < 0) break;
        out.push_back((uint8_t)((hi << 4) | lo));
    }
    return out;
}

static void onUnsol(int32_t code, const uint8_t* data, size_t len) {
    r10::IRadioIndication* ind;
    { std::lock_guard<std::mutex> lk(g_cbMu); ind = g_ind; }
    PReader pr(data, len);
    switch (code) {
    case 1000: {
        int32_t st = pr.i32();
        int32_t mapped = st == 0 ? r10::RADIO_OFF : st == 10 ? r10::RADIO_ON : r10::RADIO_UNAVAILABLE;
        g_radioState = mapped;
        LOG("unsol radioState: stub=%d hidl=%d", st, mapped);
        if (ind) ind->radioStateChanged(r10::IND_UNSOLICITED, mapped);
        break;
    }
    case 1001:
        if (ind) ind->callStateChanged(r10::IND_UNSOLICITED);
        break;
    case 1002:
        if (ind) ind->networkStateChanged(r10::IND_UNSOLICITED);
        break;
    case 1003: {
        std::string hex = pr.str();
        std::vector<uint8_t> pdu = hexDecode(hex);
        LOG("unsol newSms: %zu pdu bytes", pdu.size());
        if (ind && !pdu.empty()) ind->newSms(r10::IND_UNSOLICITED, hidl_vec<uint8_t>(pdu.data(), (uint32_t)pdu.size()));
        break;
    }
    case 1018: {
        if (ind) {
            r10::CdmaSignalInfoRecord rec{};
            rec.isPresent = false;
            ind->callRing(r10::IND_UNSOLICITED, true, rec);
            ind->callStateChanged(r10::IND_UNSOLICITED);
        }
        break;
    }
    case 1019:
        if (ind) ind->simStatusChanged(r10::IND_UNSOLICITED);
        break;
    case 1034: {
        int32_t count = pr.i32();
        int32_t version = count > 0 ? pr.i32() : -1;
        LOG("unsol rilConnected: ril version %d", version);
        break;
    }
    default:
        LOG("unsol %d (%zu bytes): ignored", code, len);
        break;
    }
}

// ---------------------------------------------------------------------------
int main() {
    LOG("starting (pid %d)", (int)getpid());
    void* hidlbase = dlopen("libhidlbase.so", RTLD_NOW);
    if (!hidlbase) { LOG("dlopen libhidlbase failed: %s", dlerror()); return 1; }
    void* libutils = dlopen("libutils.so", RTLD_NOW);
    if (!libutils) { LOG("dlopen libutils failed: %s", dlerror()); return 1; }

    typedef void (*ConfigureFn)(uint32_t, bool);
    typedef void (*JoinFn)();
    auto configure = (ConfigureFn)dlsym(hidlbase, "_ZN7android8hardware22configureRpcThreadpoolEmb");
    auto join = (JoinFn)dlsym(hidlbase, "_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!configure || !join) { LOG("libhidlbase threadpool symbols missing"); return 1; }

    g_refbase_ctor = (void (*)(void*))dlsym(libutils, "_ZN7android7RefBaseC2Ev");
    if (!g_refbase_ctor) g_refbase_ctor = (void (*)(void*))dlsym(libutils, "_ZN7android7RefBaseC1Ev");
    g_incStrong = (void (*)(const void*, const void*))dlsym(libutils, "_ZNK7android7RefBase9incStrongEPKv");
    g_decStrong = (void (*)(const void*, const void*))dlsym(libutils, "_ZNK7android7RefBase9decStrongEPKv");
    if (!g_refbase_ctor || !g_incStrong || !g_decStrong) {
        LOG("libutils RefBase symbols missing (ctor=%p inc=%p dec=%p)",
            (void*)g_refbase_ctor, (void*)g_incStrong, (void*)g_decStrong);
        return 1;
    }

    // Start the RIL connection before registering, so the first framework
    // requests find the stub already reachable.
    std::thread([] { g_ril.run(); }).detach();

    // Intentionally leaked: the service object lives until the process dies,
    // and its destructor would have to run against a half-torn-down runtime.
    RadioImpl* impl = new RadioImpl();

    configure(4, true);

    typedef int (*RegisterFn)(void*, const char*, size_t, size_t);
    // IRadio::registerAsService(const std::string&) — the string is passed as
    // libc++'s std::string, whose short form is a size byte followed by the
    // characters; build that 24-byte image by hand (as in the omxstore host).
    struct FakeString { uint8_t sizeX2; char data[23]; };
    auto tryRegister = [&](const char* lib, const char* symbol) -> int {
        void* h = dlopen(lib, RTLD_NOW);
        if (!h) { LOG("dlopen %s failed: %s", lib, dlerror()); return -100; }
        auto fn = (RegisterFn)dlsym(h, symbol);
        if (!fn) { LOG("dlsym registerAsService in %s failed: %s", lib, dlerror()); return -101; }
        FakeString name{};
        const char* inst = "slot1";
        size_t n = strlen(inst);
        name.sizeX2 = (uint8_t)(n * 2);
        memcpy(name.data, inst, n);
        return fn(impl, (const char*)&name, 0, 0);
    };
    int rc = tryRegister("android.hardware.radio@1.4.so",
        "_ZN7android8hardware5radio4V1_46IRadio17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE");
    if (rc != 0) {
        LOG("radio@1.4 registerAsService rc=%d, trying 1.2", rc);
        rc = tryRegister("android.hardware.radio@1.2.so",
            "_ZN7android8hardware5radio4V1_26IRadio17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE");
    }
    if (rc != 0) {
        LOG("radio@1.2 registerAsService rc=%d, trying 1.0", rc);
        rc = tryRegister("android.hardware.radio@1.0.so",
            "_ZN7android8hardware5radio4V1_06IRadio17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE");
    }
    LOG("registerAsService(slot1) rc=%d", rc);
    if (rc != 0) return 1;

    join();
    LOG("threadpool exited");
    return 0;
}
