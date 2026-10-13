import AppKit
import Foundation

/// AI 편집은 생성 도구에서 끝낸다. 이 명령은 선택된 결과의 픽셀을 바꾸지 않고 JPEG로 저장한다.
@main
enum CommunityImageJPEG {
    static func main() {
        let arguments = CommandLine.arguments
        guard arguments.count == 3 else {
            FileHandle.standardError.write(Data("사용법: community_image_jpeg 원본PNG 출력JPEG\n".utf8))
            exit(2)
        }
        do {
            let input = URL(fileURLWithPath: arguments[1])
            let output = URL(fileURLWithPath: arguments[2])
            guard !FileManager.default.fileExists(atPath: output.path) else {
                throw NSError(domain: "CommunityImage", code: 1, userInfo: [NSLocalizedDescriptionKey: "기존 파일을 덮어쓰지 않습니다"])
            }
            let original = try Data(contentsOf: input)
            guard let bitmap = NSBitmapImageRep(data: original), bitmap.pixelsWide > 0, bitmap.pixelsHigh > 0,
                  let jpeg = bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.88])
            else {
                throw NSError(domain: "CommunityImage", code: 2, userInfo: [NSLocalizedDescriptionKey: "사진을 JPEG로 저장할 수 없습니다"])
            }
            try jpeg.write(to: output, options: .atomic)
            let result: [String: Int] = ["width": bitmap.pixelsWide, "height": bitmap.pixelsHigh, "bytes": jpeg.count]
            let data = try JSONSerialization.data(withJSONObject: result, options: .sortedKeys)
            FileHandle.standardOutput.write(data + Data("\n".utf8))
        } catch {
            FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
            exit(1)
        }
    }
}
