import Foundation
import Security

// CI-only check of this run's generated CA. No network and no custom anchors:
// the admin record must explicitly deny it, and normal OS trust must reject it.
let path = CommandLine.arguments[1]
let pem = try String(contentsOfFile: path)
let body = pem.split(whereSeparator: \.isNewline).filter { !$0.hasPrefix("-----") }.joined()
guard let der = Data(base64Encoded: body), let certificate = SecCertificateCreateWithData(nil, der as CFData) else { fatalError("Invalid fixture CA") }
if !CommandLine.arguments.contains("--after-delete") {
    var settings: CFArray?
    guard SecTrustSettingsCopyTrustSettings(certificate, .admin, &settings) == errSecSuccess,
          let values = settings as? [[String: Any]],
          values.contains(where: { ($0[kSecTrustSettingsResult as String] as? NSNumber)?.uint32Value == SecTrustSettingsResult.deny.rawValue }) else { fatalError("Fixture CA lacks explicit admin denial") }
}
var trust: SecTrust?
guard SecTrustCreateWithCertificates(certificate, SecPolicyCreateBasicX509(), &trust) == errSecSuccess, let trust else { fatalError("Cannot create fixture trust evaluation") }
guard SecTrustSetNetworkFetchAllowed(trust, false) == errSecSuccess else { fatalError("Cannot disable trust network fetch") }
var error: CFError?
guard !SecTrustEvaluateWithError(trust, &error), error != nil else { fatalError("Fixture CA is still trusted") }
print("PASS: normal offline OS trust rejects the exact fixture CA" + (CommandLine.arguments.contains("--after-delete") ? " after certificate deletion" : "; admin Deny confirmed"))
