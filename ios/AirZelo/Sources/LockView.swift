import SwiftUI
import LocalAuthentication

/// Device authentication: Face ID / Touch ID with the device passcode as fallback. The books stay closed
/// (and iOS keeps their files encrypted) until the owner of the phone unlocks the app.
struct LockView: View {
    @EnvironmentObject var model: AppModel
    @State private var message: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "lock.shield.fill").font(.system(size: 60)).foregroundStyle(Color.accentColor)
            Text("AirZelo").font(.largeTitle.bold())
            Text("Your agency's books are stored only on this iPhone.").font(.callout).foregroundStyle(.secondary)
            if let message {
                Text(message).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center).padding(.horizontal)
            }
            Button {
                unlock()
            } label: {
                Label("Unlock", systemImage: "faceid").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(busy)
            .padding(.horizontal, 40)
            .accessibilityIdentifier("unlock")
            if model.passcodeMissing {
                Text("This iPhone has no passcode, so iOS cannot encrypt the books. Set a passcode in Settings > Face ID & Passcode.")
                    .font(.footnote).foregroundStyle(.orange).multilineTextAlignment(.center).padding(.horizontal)
            }
            Spacer()
        }
        .task { unlock() }
    }

    func unlock() {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "Use passcode"
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            if (err as? LAError)?.code == .passcodeNotSet {
                // without a passcode there is nothing to authenticate against; warn plainly instead of pretending
                model.passcodeMissing = true
                model.locked = false
            } else {
                message = err?.localizedDescription ?? "Authentication is not available."
            }
            return
        }
        busy = true
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock your agency's books") { ok, error in
            Task { @MainActor in
                busy = false
                if ok {
                    model.locked = false
                    message = nil
                } else if let e = error as? LAError, e.code != .userCancel, e.code != .systemCancel, e.code != .appCancel {
                    message = e.localizedDescription
                }
            }
        }
    }
}
