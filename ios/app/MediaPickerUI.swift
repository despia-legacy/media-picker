import AVFoundation
import ImageIO
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// One thing the user picked, before it is copied into the extension's own directory.
enum MPIncoming {
    /// A photo-library item (PHPicker). Its file is read through the item provider.
    case provider(NSItemProvider, typeIdentifier: String)
    /// A copy the document picker made (asCopy: true); it is moved, never copied twice.
    case file(URL)
    /// A camera photo, already encoded.
    case photo(Data, mime: String, ext: String)
    /// A camera recording in the picker's temporary directory.
    case movie(URL)
}

/// The platform's own pickers, presented stock: PHPickerViewController (photos and videos, no photo
/// library permission needed), UIDocumentPickerViewController (files, audio, any document, as a
/// copy), UIImagePickerController with the camera, and, for `sheet`, a UIAlertController action
/// sheet offering the three. Nothing is restyled except the optional tint.
///
/// Main actor: UIKit presentation and every delegate callback happen there.
@MainActor
final class MediaPickerPresenter: NSObject, PHPickerViewControllerDelegate, UIDocumentPickerDelegate,
    UIImagePickerControllerDelegate, UINavigationControllerDelegate, UIAdaptivePresentationControllerDelegate {
    struct Request {
        let source: String
        let types: MPTypes
        let limit: Int
        let representation: String
        let ordered: Bool
        let cameraMode: String?
        let maxDurationS: Int
        let quality: String
        let tint: UIColor?
    }

    enum Outcome {
        case picked([MPIncoming])
        case cancelled
        case failed(code: String, message: String)
    }

    private var finish: ((Outcome) -> Void)?
    private var request: Request?

    var isPresenting: Bool { finish != nil }

    static var cameraAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    /// The view controller to present from: the key window of the foreground-active scene, then its
    /// top presented controller. nil when the app is not in the foreground.
    static func presenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }),
              let root = (scene.windows.first { $0.isKeyWindow } ?? scene.windows.first)?.rootViewController else { return nil }
        var top = root
        while let p = top.presentedViewController, !p.isBeingDismissed { top = p }
        return top
    }

    /// Presents the picker; returns false (and never calls `done`) when nothing could be presented.
    func present(_ r: Request, done: @escaping (Outcome) -> Void) -> Bool {
        guard finish == nil, let from = Self.presenter() else { return false }
        request = r
        finish = done
        switch r.source {
        case "library": from.present(library(r), animated: true)
        case "files": from.present(documents(r), animated: true)
        case "camera":
            guard let c = camera(r) else {
                complete(.failed(code: "camera_unavailable", message: "this device has no camera"))
                return true
            }
            from.present(c, animated: true)
        default: from.present(sheet(r, from: from), animated: true)
        }
        return true
    }

    private func complete(_ o: Outcome) {
        let f = finish
        finish = nil
        request = nil
        f?(o)
    }

    // MARK: Builders

    private func library(_ r: Request) -> UIViewController {
        var config = PHPickerConfiguration()
        var filters: [PHPickerFilter] = []
        if r.types.wantsImages { filters.append(.images) }
        if r.types.wantsVideos { filters.append(.videos) }
        config.filter = filters.count == 1 ? filters[0] : .any(of: filters)
        config.selectionLimit = r.limit
        config.preferredAssetRepresentationMode = r.representation == "compatible" ? .compatible
            : (r.representation == "automatic" ? .automatic : .current)
        if r.ordered && r.limit != 1 { config.selection = .ordered }
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        picker.presentationController?.delegate = self
        if let t = r.tint { picker.view.tintColor = t }
        return picker
    }

    static func contentTypes(_ t: MPTypes) -> [UTType] {
        if t.isAny || t.categories.contains("document") { return [.item] }
        var out: [UTType] = []
        if t.categories.contains("image") { out.append(.image) }
        if t.categories.contains("video") { out.append(.movie) }
        if t.categories.contains("audio") { out.append(.audio) }
        for m in t.mimes {
            if m.hasSuffix("/*") {
                switch m {
                case "image/*": out.append(.image)
                case "video/*": out.append(.movie)
                case "audio/*": out.append(.audio)
                case "text/*": out.append(.text)
                default: out.append(.item)
                }
            } else if let u = UTType(mimeType: m) {
                out.append(u)
            }
        }
        return out.isEmpty ? [.item] : out
    }

    private func documents(_ r: Request) -> UIViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: Self.contentTypes(r.types), asCopy: true)
        picker.allowsMultipleSelection = r.limit != 1
        picker.shouldShowFileExtensions = true
        picker.delegate = self
        picker.presentationController?.delegate = self
        if let t = r.tint { picker.view.tintColor = t }
        return picker
    }

    private func camera(_ r: Request) -> UIViewController? {
        guard Self.cameraAvailable, let mode = r.cameraMode else { return nil }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        switch mode {
        case "photo": picker.mediaTypes = [UTType.image.identifier]
        case "video": picker.mediaTypes = [UTType.movie.identifier]
        default: picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        }
        if mode == "video" { picker.cameraCaptureMode = .video }
        if r.maxDurationS > 0 { picker.videoMaximumDuration = TimeInterval(r.maxDurationS) }
        switch r.quality {
        case "low": picker.videoQuality = .typeLow
        case "medium": picker.videoQuality = .typeMedium
        default: picker.videoQuality = .typeHigh
        }
        picker.delegate = self
        picker.presentationController?.delegate = self
        return picker
    }

    private func sheet(_ r: Request, from: UIViewController) -> UIViewController {
        let s = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        let visual = r.types.wantsImages || r.types.wantsVideos
        if visual {
            s.addAction(UIAlertAction(title: NSLocalizedString("Photo Library", comment: "media picker"), style: .default) { [weak self] _ in
                guard let self = self, let req = self.request else { return }
                from.present(self.library(req), animated: true)
            })
            if Self.cameraAvailable, r.cameraMode != nil {
                let title = r.cameraMode == "photo" ? "Take Photo" : (r.cameraMode == "video" ? "Record Video" : "Take Photo or Video")
                s.addAction(UIAlertAction(title: NSLocalizedString(title, comment: "media picker"), style: .default) { [weak self] _ in
                    guard let self = self, let req = self.request, let c = self.camera(req) else { return }
                    from.present(c, animated: true)
                })
            }
        }
        s.addAction(UIAlertAction(title: NSLocalizedString("Browse Files", comment: "media picker"), style: .default) { [weak self] _ in
            guard let self = self, let req = self.request else { return }
            from.present(self.documents(req), animated: true)
        })
        s.addAction(UIAlertAction(title: NSLocalizedString("Cancel", comment: "media picker"), style: .cancel) { [weak self] _ in
            self?.complete(.cancelled)
        })
        if let pop = s.popoverPresentationController {
            pop.sourceView = from.view
            pop.sourceRect = CGRect(x: from.view.bounds.midX, y: from.view.bounds.maxY - 1, width: 1, height: 1)
            pop.permittedArrowDirections = []
        }
        if let t = r.tint { s.view.tintColor = t }
        return s
    }

    // MARK: Delegates

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let r = request else { return }
        if results.isEmpty { return complete(.cancelled) }
        let items: [MPIncoming] = results.compactMap { res in
            let p = res.itemProvider
            if r.types.wantsVideos, p.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                let id = p.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .movie) == true } ?? UTType.movie.identifier
                return .provider(p, typeIdentifier: id)
            }
            if r.types.wantsImages, let id = p.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) == true }) {
                return .provider(p, typeIdentifier: id)
            }
            return nil
        }
        complete(.picked(items))
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        complete(.picked(urls.map { .file($0) }))
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        complete(.cancelled)
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        if let movie = info[.mediaURL] as? URL { return complete(.picked([.movie(movie)])) }
        guard let image = info[.originalImage] as? UIImage, let cg = image.cgImage else {
            return complete(.failed(code: "capture_failed", message: "the camera returned no photo"))
        }
        // JPEG with the capture's own metadata (EXIF, orientation), written by ImageIO.
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else {
            return complete(.failed(code: "capture_failed", message: "the photo could not be encoded"))
        }
        var props = (info[.mediaMetadata] as? [String: Any]) ?? [:]
        props[kCGImagePropertyOrientation as String] = Self.exifOrientation(image.imageOrientation)
        props[kCGImageDestinationLossyCompressionQuality as String] = 0.92
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else {
            return complete(.failed(code: "capture_failed", message: "the photo could not be encoded"))
        }
        complete(.picked([.photo(data as Data, mime: "image/jpeg", ext: ".jpg")]))
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        complete(.cancelled)
    }

    /// Swiped down: the picker is gone without a delegate answer.
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        complete(.cancelled)
    }

    private static func exifOrientation(_ o: UIImage.Orientation) -> Int {
        switch o {
        case .up: return 1
        case .upMirrored: return 2
        case .down: return 3
        case .downMirrored: return 4
        case .leftMirrored: return 5
        case .right: return 6
        case .rightMirrored: return 7
        case .left: return 8
        @unknown default: return 1
        }
    }
}
