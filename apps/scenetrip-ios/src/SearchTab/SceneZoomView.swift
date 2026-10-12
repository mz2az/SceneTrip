import SwiftUI

/// UIKit의 핀치·이동을 사용해 확대된 사진 안을 탐색한다 (MZ2AZ-420).
struct SceneZoomView: UIViewRepresentable {
    let image: UIImage
    @Binding var scale: CGFloat

    func makeUIView(context _: Context) -> SceneZoomScrollView {
        SceneZoomScrollView()
    }

    func updateUIView(_ scroll: SceneZoomScrollView, context _: Context) {
        let binding = $scale
        scroll.scaleChanged = { value in
            DispatchQueue.main.async {
                if abs(binding.wrappedValue - value) > 0.01 {
                    binding.wrappedValue = value
                }
            }
        }
        scroll.display(image)
        let desired = ScenePhotoRules.scale(scale)
        if abs(scroll.zoomScale - desired) > 0.01 {
            scroll.setZoomScale(desired, animated: false)
        }
    }
}

final class SceneZoomScrollView: UIScrollView, UIScrollViewDelegate {
    let imageView = UIImageView()
    var scaleChanged: ((CGFloat) -> Void)?
    private var fittedViewport = CGSize.zero

    init() {
        super.init(frame: .zero)
        delegate = self
        minimumZoomScale = ScenePhotoRules.minimumScale
        maximumZoomScale = ScenePhotoRules.maximumScale
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        backgroundColor = .black
        contentInsetAdjustmentBehavior = .never
        imageView.contentMode = .scaleAspectFit
        addSubview(imageView)
        let twice = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        twice.numberOfTapsRequired = 2
        addGestureRecognizer(twice)
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    func display(_ image: UIImage) {
        guard imageView.image !== image else { return }
        setZoomScale(minimumZoomScale, animated: false)
        imageView.image = image
        fittedViewport = .zero
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let image = imageView.image, bounds.width > 0, bounds.height > 0 else { return }
        if fittedViewport != bounds.size {
            setZoomScale(minimumZoomScale, animated: false)
            let size = ScenePhotoRules.fittedSize(image: image.size, viewport: bounds.size)
            imageView.frame = CGRect(origin: .zero, size: size)
            contentSize = size
            fittedViewport = bounds.size
            centerImage()
            contentOffset = CGPoint(x: -contentInset.left, y: -contentInset.top)
            scaleChanged?(minimumZoomScale)
        }
        centerImage()
    }

    func viewForZooming(in _: UIScrollView) -> UIView? {
        imageView
    }

    func scrollViewDidZoom(_: UIScrollView) {
        centerImage()
        scaleChanged?(zoomScale)
    }

    private func centerImage() {
        let vertical = max(0, (bounds.height - contentSize.height) / 2)
        let horizontal = max(0, (bounds.width - contentSize.width) / 2)
        let inset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
        if contentInset != inset {
            contentInset = inset
        }
    }

    @objc private func doubleTapped(_ tap: UITapGestureRecognizer) {
        guard zoomScale <= minimumZoomScale + 0.01 else {
            setZoomScale(minimumZoomScale, animated: true)
            return
        }
        let point = tap.location(in: imageView)
        let size = CGSize(width: bounds.width / ScenePhotoRules.doubleTapScale,
                          height: bounds.height / ScenePhotoRules.doubleTapScale)
        zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                        width: size.width, height: size.height), animated: true)
    }
}
